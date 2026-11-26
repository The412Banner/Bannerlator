package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import com.winlator.star.ui.deck.deckShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.contents.WrapperManager
import com.winlator.star.contents.WrapperSettingsDictionary
import com.winlator.star.linux.LinuxSettings
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.withGraphicsDriverKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The X11 driver configuration's "Detected settings" for an IMPORTED graphics wrapper (#132): one line
// per environment variable scanned out of the wrapper's binaries at import, minus what a curated row of
// the Graphics page already edits (WrapperManager.HANDLED_ENV_KEYS), debug and driver-internal keys, and
// the keys hidden for that wrapper in the wrapper manager's "Edit settings". The kind of control comes
// from WrapperSettingsDictionary, as in the classic dialog.
//
// The values live in the driver config ("graphicsDriverConfig", "k=v;k=v") under the raw env key, which
// the launch passes to the wrapper as-is. A line changes only its own key (withGraphicsDriverKeys) and
// keeps every other entry byte for byte, with ";" and "=" stripped from the value as the classic OK does.
// The whole string is read and written through a hidden row, so a game keeps a string of its own only
// while it differs from its container's, like every Graphics row.

internal object WrapperDetectedRows {
    /** The whole driver config, never shown: what the detected-settings section reads and writes. */
    const val ID_CONFIG = "graphics.wrapperDetected.config"

    private const val EXTRA = "graphicsDriverConfig"

    fun defs(): List<SettingDef> = listOf(
        hiddenDef(
            id = ID_CONFIG, category = "graphics",
            vm = VmBinding(get = { _, vm -> vm.graphicsDriverConfig }, set = { _, vm, v -> vm.graphicsDriverConfig = v }),
            game = GameBinding(
                keys = listOf(EXTRA),
                read = { e -> e.get(EXTRA)?.takeIf { it.isNotEmpty() && it != (e.container.getGraphicsDriverConfig() ?: "") } },
                write = { e, v -> e.put(EXTRA, v.takeIf { it != (e.container.getGraphicsDriverConfig() ?: "") }) },
            ),
        ),
    )
}

internal class DeckWrapperDetectedSection(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) : DeckSection {
    private val access = DefAccess(source)

    /** Detected keys per wrapper id, read off the main thread once per wrapper (again after the wrapper manager). */
    private val keysByDriver = mutableStateMapOf<String, List<String>>()
    private val reading = HashSet<String>()

    var choiceFor by mutableStateOf<String?>(null)
    var textFor by mutableStateOf<String?>(null)

    fun driverId() = access.value(GraphicsRows.ID_GFX_DRIVER)
    private fun driver() = driverId()
    private fun config() = access.value(WrapperDetectedRows.ID_CONFIG)

    /** Same gates as the X11 driver rows: a Wine game or container on X11 (never the Linux Steam client's home). */
    private fun applies(): Boolean {
        if (env.world != World.WINE || env.isLinuxEntry || env.scope == EditorScope.APP) return false
        val c = env.vm?.container
        if (env.scope == EditorScope.CONTAINER && c != null && LinuxSettings.isLinuxContainer(c)) return false
        return !env.wayland(access::value)
    }

    fun raw(key: String): String? =
        config().split(";").firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")

    fun put(key: String, value: String) {
        val clean = value.replace(";", "").replace("=", "")
        access.set(WrapperDetectedRows.ID_CONFIG, withGraphicsDriverKeys(config(), mapOf(key to clean)))
    }

    /** Reads the wrapper's detected keys; [caps] changing (the wrapper manager closed) reads them again. */
    fun load(driver: String) {
        if (driver.isEmpty() || !reading.add(driver)) return
        scope.launch {
            try {
                val keys = withContext(Dispatchers.IO) {
                    runCatching {
                        val wm = WrapperManager(env.context)
                        if (!wm.isImported(driver)) emptyList()
                        else {
                            val hidden = wm.hiddenKeys(driver)
                            wm.detectedEnvKeys(driver).filter {
                                it !in WrapperManager.HANDLED_ENV_KEYS && !WrapperManager.isDebugEnvKey(it) &&
                                    !WrapperManager.isDriverInternalEnvKey(it) && it !in hidden
                            }
                        }
                    }.getOrDefault(emptyList())
                }
                keysByDriver[driver] = keys
            } finally {
                reading.remove(driver)
            }
        }
    }

    override fun extras(): Map<String, CategoryExtra> =
        if (env.world != World.WINE || env.scope == EditorScope.APP) emptyMap()
        else mapOf("graphics" to CategoryExtra(top = ::topItems, bottom = ::bottomItems))

    // Nothing to see: reads the picked wrapper's detected keys while the Graphics page is open.
    private fun topItems(): List<PageExtra> =
        if (!applies()) emptyList() else listOf(PageExtra("wrapper-detected-probe") { DetectedProbe(this) })

    private fun bottomItems(): List<PageExtra> {
        if (!applies()) return emptyList()
        val driver = driver()
        if (!env.graphics.isImported(driver)) return emptyList()
        val keys = keysByDriver[driver]
        val head = listOf(
            PageExtra("wrapper-detected-label") { GroupLabel(L("Detected in this wrapper")) },
        )
        if (keys.isNullOrEmpty()) {
            return head + PageExtra("wrapper-detected-none") {
                GroupCard(first = true, last = true, highlight = false) {
                    DeckLine(
                        label = if (keys == null) L("Reading this wrapper's settings…") else L("No extra settings detected"),
                        hint = L("Settings scanned from the imported wrapper's files. Hide or show them in Manage graphics wrappers."),
                    )
                }
            }
        }
        return head + keys.mapIndexed { i, key ->
            PageExtra("wrapper-detected:$key") {
                GroupCard(first = i == 0, last = i == keys.lastIndex, highlight = false) { DetectedLine(this, key) }
            }
        } + PageExtra("wrapper-detected-note") {
            Text(
                L("Values are passed to the wrapper as they are; unknown ones are safe to leave blank."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
            )
        }
    }

    /** What a key's line shows: the stored value, or the dictionary's default when it is blank. */
    fun shown(key: String): String {
        val def = WrapperSettingsDictionary.defFor(key)
        val current = raw(key) ?: ""
        return when (def.type) {
            WrapperSettingsDictionary.Type.TOGGLE -> if (current == "1") "1" else "0"
            WrapperSettingsDictionary.Type.DROPDOWN -> current.ifEmpty { def.default }.ifEmpty { def.choices.firstOrNull() ?: "" }
            WrapperSettingsDictionary.Type.SLIDER -> (current.toFloatOrNull() ?: def.default.toFloatOrNull() ?: def.min).toInt().toString()
            WrapperSettingsDictionary.Type.TEXT -> current
        }
    }

    fun editable(): Boolean = access.editable(WrapperDetectedRows.ID_CONFIG)

    @Composable
    override fun Dialogs() {
        choiceFor?.let { key ->
            val def = WrapperSettingsDictionary.defFor(key)
            ChoiceDialog(def.label, def.choices, shown(key), onDismiss = { choiceFor = null }) { v ->
                choiceFor = null
                put(key, v)
            }
        }
        textFor?.let { key ->
            val def = WrapperSettingsDictionary.defFor(key)
            TextDialog(def.label, def.hint, raw(key) ?: "", onDismiss = { textFor = null }) { v ->
                textFor = null
                put(key, v)
            }
        }
    }
}

@Composable
private fun DetectedProbe(section: DeckWrapperDetectedSection) {
    val id = section.driverId()
    // The caps object is replaced once the wrapper manager closes (forgetCaps), so the keys are read again then.
    val caps = section.env.graphics.caps(id)
    LaunchedEffect(id, caps) { section.load(id) }
}

/** One detected key: a switch, a choice, a stepper or a text value, by its dictionary type. */
@Composable
private fun DetectedLine(section: DeckWrapperDetectedSection, key: String) {
    val def = WrapperSettingsDictionary.defFor(key)
    val enabled = section.editable()
    val value = section.shown(key)
    val hint = listOf(def.hint, key).filter { it.isNotBlank() && it != def.label }.joinToString(" · ")
    when (def.type) {
        WrapperSettingsDictionary.Type.TOGGLE -> DeckLine(
            label = def.label, hint = hint,
            // XMB: the line is one stop and A flips the switch (the switch itself is not a stop there).
            activate = { if (enabled) section.put(key, if (value == "1") "0" else "1") },
        ) {
            Switch(
                checked = value == "1",
                onCheckedChange = { section.put(key, if (it) "1" else "0") },
                enabled = enabled,
                modifier = Modifier.deckFocusRing(deckShape(20.dp), scaleTo = 1f),
            )
        }
        WrapperSettingsDictionary.Type.DROPDOWN -> DeckLine(
            label = def.label, hint = hint, enabled = enabled,
            onClick = { section.choiceFor = key },
        ) { ValueText(value.ifEmpty { "—" }) }
        WrapperSettingsDictionary.Type.SLIDER -> {
            val max = if (def.max > def.min) def.max else def.min + 1f
            val step = if (def.step > 0f) def.step else 1f
            val n = value.toFloatOrNull() ?: def.min
            DeckLine(label = def.label, hint = hint) {
                DeckSmallButton("", Icons.Filled.Remove, enabled = enabled && n > def.min) {
                    section.put(key, (n - step).coerceIn(def.min, max).toInt().toString())
                }
                ValueText(n.toInt().toString())
                DeckSmallButton("", Icons.Filled.Add, enabled = enabled && n < max) {
                    section.put(key, (n + step).coerceIn(def.min, max).toInt().toString())
                }
            }
        }
        WrapperSettingsDictionary.Type.TEXT -> DeckLine(
            label = def.label, hint = hint, enabled = enabled,
            onClick = { section.textFor = key },
        ) { ValueText(value.ifEmpty { L("Not set") }) }
    }
}

@Composable
private fun ValueText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp),
    )
}

/** A dictionary key's choices, one line each; the whole line is the focus target and A picks it. */
@Composable
private fun ChoiceDialog(title: String, choices: List<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(choices, key = { it }) { c ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (c == choices.firstOrNull()) Modifier.focusRequester(first) else Modifier)
                            .deckFocusRing(deckShape(10.dp), scaleTo = 1f)
                            .clickable { onPick(c) }
                            .padding(end = 8.dp),
                    ) {
                        RadioButton(selected = c == current, onClick = null)
                        Text(c, fontSize = 14.5.sp)
                    }
                }
            }
        },
    )
}

/** A free value for a key the dictionary types as text (a number, a name, a path). Blank leaves it unset. */
@Composable
private fun TextDialog(title: String, hint: String, current: String, onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onUse(text.trim()) }) { Text(L("Use")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    hint.ifBlank { L("Passed to the wrapper as it is. Leave blank to leave it unset.") } +
                        L(" \";\" and \"=\" are left out."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.5.sp,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
                if (text.any { it == ';' || it == '=' }) {
                    Text(
                        L("Will be saved as: ") + text.replace(";", "").replace("=", "").trim().ifEmpty { L("(blank)") }.let {
                            if (it.length > 60) it.take(60) + "…" else it
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                    )
                }
            }
        },
    )
}
