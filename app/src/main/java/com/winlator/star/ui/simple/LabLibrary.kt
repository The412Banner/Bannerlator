package com.winlator.star.ui.simple

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Shortcut
import com.winlator.star.linux.LinuxRuntimeInstaller
import com.winlator.star.linux.LinuxRuntimeUpdate
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.store.xbox.XboxCloudShortcuts
import com.winlator.star.ui.deck.DeckStore
import com.winlator.star.ui.deck.LibrarySort
import com.winlator.star.ui.deck.setupSummary
import com.winlator.star.ui.screens.buildLaunchSpec
import com.winlator.star.ui.screens.isCustomOriginShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lab's Library choices: the order, the store shown and whether games are being picked for removal. Kept in prefs lab_library. */
internal object LabLibrary {
    /** The orders the Library offers (Classic's three and Lab's last played, the default). */
    val SORTS = listOf(LibrarySort.RECENT, LibrarySort.NAME, LibrarySort.NAME_DESC, LibrarySort.CONTAINER)

    /** The store filters: Classic's store pills, by the same checks. "" = every game. */
    val FILTERS: List<Triple<String, String, (Shortcut) -> Boolean>> = listOf(
        Triple("windows_steam", "Windows Steam", { s: Shortcut -> com.winlator.star.store.WindowsSteamClient.isWindowsSteamShortcut(s) }),
        Triple("steam", "Steam", DeckStore.STEAM.owns),
        Triple("epic", "Epic Games", DeckStore.EPIC.owns),
        Triple("gog", "GOG", DeckStore.GOG.owns),
        Triple("amazon", "Amazon Games", DeckStore.AMAZON.owns),
        Triple("xbox", "Xbox", DeckStore.XBOX.owns),
        Triple("custom", T("Added by me"), { s: Shortcut -> isCustomOriginShortcut(s) }),
        Triple("linux", "Linux", { s: Shortcut -> LinuxShortcuts.isLinuxEntry(s) }),
        Triple("android", "Android", { s: Shortcut -> AndroidGames.isAndroidEntry(s) }),
    )

    private var prefs: SharedPreferences? = null
    var sort by mutableStateOf(LibrarySort.RECENT); private set
    var filter by mutableStateOf(""); private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("lab_library", Context.MODE_PRIVATE)
        prefs = p
        sort = SORTS.firstOrNull { it.name == p.getString("sort", null) } ?: LibrarySort.RECENT
        filter = p.getString("filter", "")?.takeIf { v -> FILTERS.any { it.first == v } } ?: ""
    }

    fun chooseSort(v: LibrarySort) { sort = v; prefs?.edit()?.putString("sort", v.name)?.apply() }
    fun chooseFilter(v: String) { filter = v; prefs?.edit()?.putString("filter", v)?.apply() }

    fun sortLabel(v: LibrarySort): String = when (v) {
        LibrarySort.RECENT -> L("Last played")
        LibrarySort.NAME -> L("Name A–Z")
        LibrarySort.NAME_DESC -> L("Name Z–A")
        LibrarySort.CONTAINER -> L("Container")
        else -> L(v.label)
    }
    fun filterLabel(v: String): String = FILTERS.firstOrNull { it.first == v }?.second?.let { L(it) } ?: L("All")
    fun filterOf(v: String): ((Shortcut) -> Boolean)? = FILTERS.firstOrNull { it.first == v }?.third
}

/**
 * The game's setup as chips: container, driver version, DXVK, renderer (Vulkan (Wayland) only when the game really runs
 * on Wayland), frame generation, VKD3D, x86 backend. Classic's list card and the launch screen read the same values
 * (buildLaunchSpec). Android games and the Linux runtime have none of these: their own summary.
 */
internal fun labSetupChips(context: Context, s: Shortcut): List<String> {
    val own = { setupSummary(s).split(" · ").filter { it.isNotBlank() } }
    if (AndroidGames.packageOf(s) != null || LinuxShortcuts.isLinuxEntry(s)) return own()
    val spec = runCatching { buildLaunchSpec(s, context) }.getOrNull() ?: return own()
    return listOfNotNull(
        s.container?.name?.takeIf { it.isNotBlank() },
        spec.driverLabel.takeIf { it.isNotBlank() },
        spec.dxvkVersion.takeIf { it.isNotBlank() }?.let { "DXVK $it" },
        spec.rendererLabel.takeIf { it.isNotBlank() },
        spec.frameGenLabel.takeIf { it.isNotBlank() },
        spec.vkd3dVersion.takeIf { it.isNotBlank() }?.let { "VKD3D $it" },
        spec.backendLabel.takeIf { it.isNotBlank() },
    )
}

/** The short line under a Library card's name, as Classic's grid tile has it: the container; an Xbox game not downloaded yet says so. */
internal fun labCardLine(s: Shortcut): String? = when {
    XboxCloudShortcuts.isNotInstalled(s) -> L("Not installed · Stream")
    AndroidGames.isAndroidEntry(s) -> null
    else -> s.container?.name?.takeIf { it.isNotBlank() }
}

/**
 * The Linux runtime entry's state, as Classic's tile shows it: while an install runs the Contents page's
 * "Downloading 45% · about 2 min left" and a bar, else "Update to X" when a newer build is waiting (a tap installs it).
 * Asks the catalog once, so it shows without Contents being opened. Nothing for any other game.
 */
@Composable
internal fun LabRuntimeStatus(s: Shortcut, fontSize: TextUnit = 10.sp, color: Color = Color.White) {
    val linux = remember(s.file.path) { LinuxShortcuts.isLinuxEntry(s) }
    if (!linux) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt by LinuxRuntimeUpdate.state.collectAsState()
    LaunchedEffect(Unit) {
        LinuxRuntimeUpdate.refreshInstalled(context)
        if (LinuxRuntimeUpdate.state.value.available == null) {
            val rel = withContext(Dispatchers.IO) { runCatching { LinuxRuntimeInstaller.fetchRelease() }.getOrNull() }
            LinuxRuntimeUpdate.setAvailable(rel)
        }
    }
    when {
        rt.busy -> Column {
            Text(LinuxRuntimeUpdate.line(rt), color = color, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LabBar(if (rt.percent in 0..100) rt.percent / 100f else 0f, Modifier.fillMaxWidth().padding(top = 3.dp))
        }
        rt.updateAvailable -> Text(
            L("Update to %1\$s", rt.available),
            color = Color.White, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
                .background(MaterialTheme.colorScheme.primary, SimpleShape)
                .clickable { scope.launch { LinuxRuntimeUpdate.runInstall(context) } }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
