package com.winlator.star.ui.simple

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.core.HttpUtils
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.LayoutPreviewFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Where Classic's Download Profiles list (index.txt) and its .icp files come from. */
internal const val COMMUNITY_PROFILES_URL = "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/"

private fun profileTitle(file: String) = file.removeSuffix(".icpx").removeSuffix(".icp")

/** A profile's on-screen layout as a small picture (Classic's On-Screen preview), read off the main thread; a tap runs [onTap]. */
@Composable
internal fun LabProfileThumb(profileId: Int, refreshKey: Int, onTap: () -> Unit) {
    val context = LocalContext.current
    // A fresh instance per picture: the preview lays its elements out against its own small size.
    val profile by produceState<ControlsProfile?>(null, profileId, refreshKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { InputControlsManager.loadProfile(context, ControlsProfile.getProfileFile(context, profileId)) }.getOrNull()
        }
    }
    LayoutPreviewFrame(profile = profile, onTap = onTap)
}

/**
 * Classic's Download Profiles as a Lab list: A ticks a profile, X (or the eye) shows its layout first, Download fetches
 * and imports the ticked ones. [items] is null while the list loads.
 */
@Composable
internal fun LabCommunityProfilesDialog(items: List<String>?, manager: InputControlsManager, onDismiss: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var picked by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<String?>(null) }
    // Files fetched for a preview, so importing them needs no second download.
    val cache = remember { mutableMapOf<String, String>() }
    val first = remember { FocusRequester() }
    LaunchedEffect(items?.size) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    fun toggle(f: String) { picked = if (f in picked) picked - f else picked + f }
    fun download() {
        val files = items.orEmpty().filter { it in picked }
        if (files.isEmpty()) return
        onDismiss()
        toast(context, if (files.size == 1) L("Downloading %1\$s…", profileTitle(files[0])) else L("Downloading %1\$s profiles…", files.size))
        val done = AtomicInteger()
        val failed = AtomicInteger()
        files.forEach { f ->
            val importOne: (String?) -> Unit = { content ->
                val ok = content != null && runCatching { manager.importProfile(JSONObject(content)) != null }.getOrDefault(false)
                if (!ok) failed.incrementAndGet()
                if (done.incrementAndGet() == files.size) (context as? Activity)?.runOnUiThread {
                    onImported()
                    val bad = failed.get()
                    toast(
                        context,
                        when {
                            bad > 0 -> L("%1\$s of %2\$s profiles could not be imported", bad, files.size)
                            files.size == 1 -> L("Imported %1\$s", profileTitle(files[0]))
                            else -> L("Imported %1\$s profiles", files.size)
                        },
                    )
                }
            }
            val cached = cache[f]
            if (cached != null) Thread { importOne(cached) }.start()
            else HttpUtils.download(COMMUNITY_PROFILES_URL + f) { content -> importOne(content) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(L("Community profiles"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            when {
                items == null -> Text(L("Loading the list…"), color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                items.isEmpty() -> Text(L("Nothing in the list."), color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                else -> Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    items.forEachIndexed { i, f ->
                        val on = f in picked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .labTile(onClick = { toggle(f) }, onMenu = { preview = f }, requester = if (i == 0) first else null)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(if (on) accent else androidx.compose.ui.graphics.Color.Transparent, SimpleShape)
                                    .border(1.5.dp, if (on) accent else SimpleMuted, SimpleShape),
                            ) { if (on) Text("✓", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(12.dp))
                            Text(profileTitle(f), color = SimpleStrong, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).simpleTap { preview = f }) {
                                Icon(Icons.Filled.Visibility, contentDescription = L("View example"), tint = accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (!items.isNullOrEmpty()) {
                    LabButton(if (picked.isEmpty()) L("Download") else L("Download (%1\$s)", picked.size), primary = picked.isNotEmpty(), onClick = { download() })
                }
                LabButton(L("Close"), onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                if (!items.isNullOrEmpty()) SimpleLegend(listOf("A" to L("Select"), "X" to L("View example")))
            }
        }
    }

    preview?.let { f ->
        LabCommunityPreview(
            file = f, cache = cache, picked = f in picked,
            onPick = { picked = picked + f; preview = null },
            onClose = { preview = null },
        )
    }
}

/** "View example": a community profile's layout before it is imported (Classic's preview popover). */
@Composable
private fun LabCommunityPreview(file: String, cache: MutableMap<String, String>, picked: Boolean, onPick: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var profile by remember(file) { mutableStateOf<ControlsProfile?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(file) {
        val toProfile: (String?) -> ControlsProfile? = { c -> if (c == null) null else runCatching { ControlsProfile.createPreview(context, JSONObject(c)) }.getOrNull() }
        val cached = cache[file]
        if (cached != null) {
            profile = toProfile(cached)
            failed = profile == null
        } else HttpUtils.download(COMMUNITY_PROFILES_URL + file) { content ->
            (context as? Activity)?.runOnUiThread {
                if (content != null) cache[file] = content
                profile = toProfile(content)
                failed = profile == null
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
    }
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 620.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(profileTitle(file), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            val p = profile
            when {
                p != null -> Box {
                    LayoutPreviewFrame(profile = p, onTap = null)
                    // Keeps touches off the overlay view underneath (it is only a picture here).
                    Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                }
                failed -> Text(L("Could not download it."), color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                else -> Text(L("Loading…"), color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                LabButton(if (picked) L("Selected") else L("Select"), primary = !picked, requester = focus, onClick = onPick)
                LabButton(L("Close"), onClick = onClose)
            }
        }
    }
}
