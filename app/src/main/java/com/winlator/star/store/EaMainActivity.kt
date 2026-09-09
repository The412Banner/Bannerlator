package com.winlator.star.store

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.AppOrientation
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The EA section — a reference page, not a shop.
 *
 * EA games arrive through Steam; nothing is bought, downloaded or signed into here. What EA
 * controls is *permission*, and this screen answers the one question you could otherwise only
 * settle by launching: **what will this title do when I start it?**
 *
 * Three answers, each read from files already on the device — no EA account, no network:
 *
 * - **Signs in every launch.** The game carries its own activation client (`Core/ActivationUI.exe`
 *   plus the Qt web stack) and asks EA directly, every time. Nothing on the device changes that: it
 *   is the game's own protection and it consults no local launcher at all.
 * - **Licence kept on device.** No such client, so the title goes through EA Desktop, which stores
 *   the licence and stops asking.
 * - **Cannot run.** Anti-cheat needing a Windows kernel driver, which cannot exist under this
 *   runtime. No container, driver or setting changes it.
 *
 * That split is the whole content of the screen, and it is why it earns its place: before it, the
 * only way to learn which group a title fell into was to install it, launch it and find out.
 */
class EaMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppOrientation.apply(this)
        setContent { WinlatorTheme { EaScreen(onBack = { finish() }) } }
    }
}

/** What a title does when you start it. Declared worst-surprise-first — that is the display order. */
private enum class EaBehaviour { SIGN_IN_EACH_LAUNCH, LICENCE_KEPT, CANNOT_RUN }

private data class EaTitle(val name: String, val container: String, val behaviour: EaBehaviour)

@Composable
private fun EaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current

    // Scanning reads game folders, so it runs off the main thread and the list appears when ready.
    val titles by produceState(initialValue = emptyList<EaTitle>(), ctx) {
        value = withContext(Dispatchers.IO) { scanEaTitles(ctx) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "EA",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (titles.isEmpty()) {
                Text(
                    "No EA games found. They appear here once an EA-published title from your Steam " +
                        "library is installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    for (group in EaBehaviour.values()) {
                        val inGroup = titles.filter { it.behaviour == group }
                        if (inGroup.isEmpty()) continue
                        item(key = "h-${group.name}") { GroupHeader(group) }
                        items(inGroup, key = { "${group.name}|${it.container}|${it.name}" }) {
                            TitleRow(it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: EaBehaviour) {
    Text(
        when (group) {
            EaBehaviour.SIGN_IN_EACH_LAUNCH -> "SIGNS IN EVERY LAUNCH"
            EaBehaviour.LICENCE_KEPT -> "LICENCE KEPT ON DEVICE"
            EaBehaviour.CANNOT_RUN -> "CANNOT RUN HERE"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun TitleRow(title: EaTitle) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when (title.behaviour) {
                    EaBehaviour.SIGN_IN_EACH_LAUNCH ->
                        "Has its own activation client — asks EA directly, every time"
                    EaBehaviour.LICENCE_KEPT ->
                        "Goes through EA Desktop, which keeps the licence"
                    EaBehaviour.CANNOT_RUN ->
                        "Anti-cheat needs a Windows driver — no setting changes this"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (title.behaviour) {
                    EaBehaviour.CANNOT_RUN -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (title.container.isNotEmpty()) {
            Text(
                title.container,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * Every EA-tagged shortcut, sorted into the three behaviours.
 *
 * Goes through [EaSupport.detectForShortcut] rather than reading the tags directly, because that is
 * what re-reads the game folder for a shortcut written before the richer detection existed. Reading
 * the tags alone reports "no anti-cheat" for those, which is not a negative result — it is an
 * unasked question, and it is how Unbound came to be listed as launchable.
 */
private fun scanEaTitles(ctx: android.content.Context): List<EaTitle> = try {
    ContainerManager(ctx).loadShortcuts()
        .filter { EaSupport.isTagged(it) }
        .map { shortcut ->
            val profile = runCatching { EaSupport.detectForShortcut(shortcut) }.getOrNull()
            EaTitle(
                name = shortcut.name,
                container = runCatching { shortcut.container?.getName().orEmpty() }.getOrDefault(""),
                behaviour = when {
                    profile?.javelinAntiCheat == true -> EaBehaviour.CANNOT_RUN
                    profile?.ownActivationClient == true -> EaBehaviour.SIGN_IN_EACH_LAUNCH
                    else -> EaBehaviour.LICENCE_KEPT
                },
            )
        }
        .sortedBy { it.name.lowercase() }
} catch (t: Throwable) {
    android.util.Log.w("BL_EA", "could not list EA titles", t)
    emptyList()
}
