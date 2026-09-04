package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.box64.Box64Preset
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.core.EnvVars
import com.winlator.star.core.FileUtils
import com.winlator.star.core.StringUtils
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.fexcore.FEXCorePresetManager
import org.json.JSONArray
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// PresetEditDialog — the Compose replacement for Box64EditPresetDialog and
// FEXCoreEditPresetDialog (both plain android.app.Dialog subclasses inflating
// XML). Those took their colours from the AppCompat theme, whose accent is a
// hardcoded #0055FF, so they stayed blue no matter which app theme was active.
// This draws from MaterialTheme instead and therefore follows the theme.
//
// Behaviour is deliberately identical to the dialogs it replaces: the variable
// list and its widget kinds come from <prefix>_env_vars.json, current values
// come from the preset manager (falling back to each variable's defaultValue),
// and saving routes through the same editPreset(). Built-in presets stay
// read-only — editPreset only rewrites entries in the custom-preset list, so a
// built-in id is a no-op there; we disable the inputs and hide Save to make
// that visible rather than silent.
// ─────────────────────────────────────────────────────────────────────────────

internal enum class PresetKind(val prefix: String, val titleRes: Int) {
    BOX64("box64", R.string.box64_preset),
    FEXCORE("fexcore", R.string.fexcore_preset);
}

/** What the editor is open on: a brand-new preset, or an existing one by id. */
internal sealed class PresetEditTarget(val id: String?) {
    data object New : PresetEditTarget(null)
    class Existing(id: String) : PresetEditTarget(id)
}

/** One row of <prefix>_env_vars.json. */
private data class VarSpec(
    val name: String,
    val values: List<String>,
    val kind: Kind,
    val defaultValue: String,
) {
    enum class Kind { TOGGLE, DROPDOWN, TEXT }
}

private fun loadSpecs(context: Context, prefix: String): List<VarSpec> = try {
    val data = JSONArray(FileUtils.readString(context, prefix + "_env_vars.json"))
    (0 until data.length()).map { i ->
        val item = data.getJSONObject(i)
        val values = item.optJSONArray("values")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        // The JSON is inconsistent about the casing of these two flags (both
        // "toggleSwitch" and "toggleswitch" appear, and some values are the
        // strings "true"/"false" rather than booleans), so accept either.
        val toggle = item.optBoolean("toggleSwitch", false) ||
            item.optBoolean("toggleswitch", false) ||
            item.optString("toggleSwitch") == "true" ||
            item.optString("toggleswitch") == "true"
        val text = item.optBoolean("editText", false) || item.optString("editText") == "true"
        VarSpec(
            name = item.getString("name"),
            values = values,
            kind = when {
                toggle -> VarSpec.Kind.TOGGLE
                text -> VarSpec.Kind.TEXT
                else -> VarSpec.Kind.DROPDOWN
            },
            defaultValue = item.optString("defaultValue", values.firstOrNull() ?: ""),
        )
    }
} catch (_: Throwable) {
    emptyList()
}

/** Per-variable help string, e.g. box64_env_var_help__dynarec_bigblock. */
private fun varHelp(context: Context, prefix: String, name: String): String? {
    val suffix = name.replace(prefix.uppercase(Locale.ENGLISH) + "_", "").lowercase(Locale.ENGLISH)
    return StringUtils.getString(context, prefix + "_env_var_help__" + suffix)
}

/** Per-preset help string, e.g. box64_preset_help__extreme_2 / fexcore_preset_help__extreme_tso. */
private fun presetHelp(context: Context, prefix: String, id: String?): String? {
    if (id == null) return null
    val key = id.substringBefore('-').lowercase(Locale.ENGLISH)
    return StringUtils.getString(context, prefix + "_preset_help__" + key)
}

@Composable
internal fun PresetEditDialog(
    kind: PresetKind,
    presetId: String?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val prefix = kind.prefix

    val existingName = remember(presetId) {
        when {
            presetId == null -> null
            kind == PresetKind.BOX64 -> Box64PresetManager.getPreset(prefix, context, presetId)?.name
            else -> FEXCorePresetManager.getPreset(context, presetId)?.name
        }
    }
    val isCustom = remember(presetId) {
        presetId != null && presetId.startsWith(
            if (kind == PresetKind.BOX64) Box64Preset.CUSTOM else FEXCorePreset.CUSTOM
        )
    }
    // Built-in presets are editable; their values are stored as an override so Reset can restore
    // the shipped ones. Only the NAME is locked, since it comes from a string resource.
    val isBuiltIn = presetId != null && !isCustom
    var modified by remember(presetId) {
        mutableStateOf(
            presetId != null && when (kind) {
                PresetKind.BOX64 -> Box64PresetManager.hasOverride(prefix, context, presetId)
                PresetKind.FEXCORE -> FEXCorePresetManager.hasOverride(context, presetId)
            }
        )
    }

    val specs = remember(prefix) { loadSpecs(context, prefix) }
    val current = remember(presetId) {
        when {
            presetId == null -> null
            kind == PresetKind.BOX64 -> Box64PresetManager.getEnvVars(prefix, context, presetId)
            else -> FEXCorePresetManager.getEnvVars(context, presetId)
        }
    }

    // One entry per variable, seeded from the preset (or its default) — this is the
    // edit buffer, so Cancel simply drops it and nothing is written.
    val values = remember(presetId, specs) {
        mutableStateMapOf<String, String>().apply {
            specs.forEach { spec ->
                put(spec.name, current?.takeIf { it.has(spec.name) }?.get(spec.name) ?: spec.defaultValue)
            }
        }
    }

    var name by remember(presetId) {
        mutableStateOf(
            existingName ?: (context.getString(R.string.preset) + "-" + when (kind) {
                PresetKind.BOX64 -> Box64PresetManager.getNextPresetId(context, prefix)
                PresetKind.FEXCORE -> FEXCorePresetManager.getNextPresetId(context)
            })
        )
    }

    var helpText by remember { mutableStateOf<String?>(null) }
    helpText?.let { HelpTextDialog(it) { helpText = null } }

    val presetHelpText = remember(presetId) { presetHelp(context, prefix, presetId) }

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(kind.titleRes), fontWeight = FontWeight.SemiBold)
                    if (presetHelpText != null) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { helpText = presetHelpText },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Outlined.HelpOutline,
                                contentDescription = "About this preset",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (isBuiltIn) {
                    Text(
                        if (modified) "Built-in preset — edited. Reset restores the original."
                        else "Built-in preset — edits are saved separately and can be reset.",
                        fontSize = 11.sp,
                        color = if (modified) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    // Built-in names come from string resources, so only custom presets rename.
                    enabled = !isBuiltIn,
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Environment variables",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp),
                ) {
                    items(specs, key = { it.name }) { spec ->
                        VarRow(
                            spec = spec,
                            value = values[spec.name] ?: spec.defaultValue,
                            enabled = true,
                            onValue = { values[spec.name] = it },
                            onHelp = { varHelp(context, prefix, spec.name)?.let { h -> helpText = h } },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = name.trim().replace(Regex("[,|]+"), "")
                if (clean.isEmpty()) return@TextButton
                val envVars = EnvVars()
                specs.forEach { envVars.put(it.name, values[it.name] ?: it.defaultValue) }
                when (kind) {
                    PresetKind.BOX64 -> Box64PresetManager.editPreset(prefix, context, presetId, clean, envVars)
                    PresetKind.FEXCORE -> FEXCorePresetManager.editPreset(context, presetId, clean, envVars)
                }
                onSaved()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Reset only makes sense for a built-in that has actually been edited — a custom
                // preset has no shipped original to go back to.
                if (isBuiltIn && modified) {
                    TextButton(onClick = {
                        when (kind) {
                            PresetKind.BOX64 -> Box64PresetManager.resetPreset(prefix, context, presetId)
                            PresetKind.FEXCORE -> FEXCorePresetManager.resetPreset(context, presetId)
                        }
                        // Re-seed the editor from the now-restored shipped values so the change
                        // is visible immediately rather than only after reopening.
                        val restored = when (kind) {
                            PresetKind.BOX64 -> Box64PresetManager.getEnvVars(prefix, context, presetId!!)
                            PresetKind.FEXCORE -> FEXCorePresetManager.getEnvVars(context, presetId!!)
                        }
                        specs.forEach { spec ->
                            values[spec.name] =
                                restored.takeIf { it.has(spec.name) }?.get(spec.name) ?: spec.defaultValue
                        }
                        modified = false
                        onSaved()
                    }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun VarRow(
    spec: VarSpec,
    value: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
    onHelp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            spec.name,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onHelp, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.HelpOutline,
                contentDescription = "About " + spec.name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        when (spec.kind) {
            VarSpec.Kind.TOGGLE -> Switch(
                checked = value == "1",
                onCheckedChange = { onValue(if (it) "1" else "0") },
                enabled = enabled,
            )

            VarSpec.Kind.TEXT -> OutlinedTextField(
                value = value,
                onValueChange = onValue,
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(96.dp),
            )

            VarSpec.Kind.DROPDOWN -> {
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .width(96.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .clickable(enabled = enabled) { open = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            value,
                            fontSize = 12.sp,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = open,
                        onDismissRequest = { open = false },
                        // Same outlined menu card the rest of the app uses, so the value picker
                        // is outlined like every other popup.
                        modifier = Modifier.outlinedMenuCard(),
                    ) {
                        spec.values.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v, fontSize = 12.sp) },
                                onClick = { onValue(v); open = false },
                            )
                        }
                    }
                }
            }
        }
    }
}
