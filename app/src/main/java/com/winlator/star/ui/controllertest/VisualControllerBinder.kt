package com.winlator.star.ui.controllertest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.inputcontrols.Binding
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.ExternalController

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Shared VISUAL controller-binding editor. Tap a button on the drawn pad → bind it (keyboard / mouse /
// Xbox / none), written straight to the profile's ExternalController.controllerBindings via BindTargets
// (the SAME store the list editor uses). Surfaces the pass-through gotcha (banner + Fill-native) and
// hosts profile pick/create/rename. Reuses the per-family pad art + auto-detect. Used by BOTH the
// Settings screen and the in-game Players popup.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

private val WARN = Color(0xFFE7B64C)

@Composable
fun VisualControllerBinder(
    profile: ControlsProfile?,
    art: PadArt,
    snapshot: ControllerTestSnapshot?,
    allProfiles: List<ControlsProfile>,
    onSelectProfile: (ControlsProfile) -> Unit,
    onCreateProfile: (String) -> Unit,
    onRenameProfile: () -> Unit,
    onSaved: () -> Unit,
    onOpenAllBindings: (() -> Unit)?,
    // When non-null, the profile picker shows a "— None —" entry (physical-lane passthrough). Left null
    // by the at-rest Settings editors, which are edit-only and have no live pad to revert.
    onSelectNone: (() -> Unit)? = null,
    // When non-null, the profile picker shows a "Delete…" action (alongside Rename…). Null = no delete
    // (the host handles the confirm + removal).
    onDeleteProfile: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Bump after each write so the controller-backed reads below recompose.
    var bindRev by remember { mutableIntStateOf(0) }
    val controller: ExternalController? = remember(profile, bindRev) { profile?.let { resolveBindController(it) } }

    var selectedId by remember(profile) { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf(BindCategory.KEYBOARD) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var profMenu by remember { mutableStateOf(false) }

    fun bumpSaved() { bindRev++; onSaved() }

    val boundN = remember(bindRev, controller) { boundCount(controller) }
    val labels = remember(bindRev, controller) {
        BIND_TARGETS.associate { it.id to bindingLabelOrNull(controller, it.id) }
    }
    // BindTarget ids that are currently bound (PadArtView maps them to manifest coords for the dots).
    val boundBindIds = remember(bindRev, controller) {
        labels.filterValues { it != null }.keys.toSet()
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        // ── Toolbar: profile picker + new/rename + All-bindings ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Profile", fontSize = 11.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(
                    onClick = { profMenu = true },
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.widthIn(max = 180.dp),
                ) {
                    Text(profile?.name ?: "— none —", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(
                    expanded = profMenu,
                    onDismissRequest = { profMenu = false },
                    modifier = Modifier.border(1.dp, cs.outline, RoundedCornerShape(8.dp)),
                ) {
                    val divider = cs.outline.copy(alpha = 0.4f)
                    if (onSelectNone != null) {
                        DropdownMenuItem(text = { Text("— None (native Xbox) —") }, onClick = { profMenu = false; onSelectNone() })
                        HorizontalDivider(color = divider)
                    }
                    allProfiles.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { profMenu = false; onSelectProfile(p) })
                        HorizontalDivider(color = divider)
                    }
                    if (profile != null) {
                        DropdownMenuItem(text = { Text("Rename…") }, onClick = { profMenu = false; onRenameProfile() })
                        if (onDeleteProfile != null) {
                            HorizontalDivider(color = divider)
                            DropdownMenuItem(
                                text = { Text("Delete…", color = cs.error) },
                                onClick = { profMenu = false; onDeleteProfile() },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showNew = !showNew }) { Text("+ New", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            if (onOpenAllBindings != null) {
                TextButton(onClick = onOpenAllBindings) { Text("All bindings", fontSize = 12.sp) }
            }
        }
        if (showNew) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("New profile name", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (newName.isNotBlank()) { onCreateProfile(newName.trim()); newName = ""; showNew = false }
                }, shape = RoundedCornerShape(9.dp)) { Text("Create", fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (profile == null || controller == null) {
            Text(
                "Select or create a profile to bind this controller.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        } else {

        // ── Pass-through banner ──
        if (boundN > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .background(WARN.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .border(1.dp, WARN.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Remapping is ON — $boundN of ${BIND_TARGETS.size} mapped", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = WARN)
                    Text("Unbound buttons go SILENT in-game (they no longer act as native Xbox).", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { fillNative(controller, profile); bumpSaved() },
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) { Text("Fill → native", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Pad + edit panel — side-by-side when there's width (wide Settings card), stacked when
        // narrow (the in-game popup's right column, or portrait). ──
        val padBox: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PadArtView(
                    art = art,
                    snapshot = snapshot,
                    boundBindIds = boundBindIds,
                    selectedBindId = selectedId,
                    onTapBind = { id -> selectedId = id },
                )
            }
        }
        val panel: @Composable () -> Unit = {
            if (selectedId == null) {
                BindSummary(labels, boundN) { id -> selectedId = id }
            } else {
                BindEditor(
                    id = selectedId!!,
                    currentLabel = labels[selectedId!!],
                    category = category,
                    onCategory = { category = it },
                    onPick = { bnd -> setTarget(controller, profile, selectedId!!, bnd); bumpSaved() },
                    onNative = {
                        val nat = BIND_BY_ID[selectedId!!]?.native
                        if (nat != null) { setTarget(controller, profile, selectedId!!, nat); bumpSaved() }
                    },
                    onClear = { clearTarget(controller, profile, selectedId!!); bumpSaved() },
                    onDone = { selectedId = null },
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 520.dp) {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { padBox() }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { panel() }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    padBox()
                    Spacer(Modifier.height(12.dp))
                    panel()
                }
            }
        }
        } // end else (profile + controller present)
    }
}

@Composable
private fun BindSummary(labels: Map<String, String?>, boundN: Int, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text("Bindings · $boundN of ${BIND_TARGETS.size} mapped", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Spacer(Modifier.height(6.dp))
        BIND_TARGETS.forEach { t ->
            val label = labels[t.id]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .background(cs.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(9.dp))
                    .clickable { onSelect(t.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(t.name, fontSize = 12.sp, color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("→", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    label ?: "native",
                    fontSize = 11.sp,
                    color = if (label != null) BIND_BLUE else cs.onSurfaceVariant,
                    fontWeight = if (label != null) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun BindEditor(
    id: String,
    currentLabel: String?,
    category: BindCategory,
    onCategory: (BindCategory) -> Unit,
    onPick: (Binding) -> Unit,
    onNative: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val t = BIND_BY_ID[id]
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Bind: ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(t?.name ?: id, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BIND_BLUE, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done", fontSize = 12.sp) }
        }
        Text(
            "Currently: " + (currentLabel ?: "native (pass-through)"),
            fontSize = 12.sp,
            color = if (currentLabel != null) BIND_BLUE else cs.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        // Category tabs.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            BindCategory.values().forEach { cat ->
                val on = cat == category
                Box(
                    Modifier
                        .background(if (on) BIND_BLUE.copy(alpha = 0.2f) else cs.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .border(1.dp, if (on) BIND_BLUE else cs.outline, RoundedCornerShape(8.dp))
                        .clickable { onCategory(cat) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        when (cat) { BindCategory.KEYBOARD -> "Keyboard"; BindCategory.MOUSE -> "Mouse"; BindCategory.XBOX -> "Xbox"; BindCategory.NONE -> "None" },
                        fontSize = 11.sp, color = if (on) cs.onSurface else cs.onSurfaceVariant, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Option chips grid (chunked rows; the whole binder scrolls, so no inner scroll).
        val labels = categoryLabels(category)
        val values = categoryValues(category)
        val n = minOf(labels.size, values.size)
        val perRow = 4
        var i = 0
        while (i < n) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                var col = 0
                while (col < perRow && i < n) {
                    val idx = i
                    OutlinedButton(
                        onClick = { onPick(values[idx]) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(labels[idx], fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    i++; col++
                }
                // Pad the final row so the last cells keep their width.
                while (col < perRow) { Spacer(Modifier.weight(1f)); col++ }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (t?.native != null) {
                OutlinedButton(onClick = onNative, shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("Native (${t.name})", fontSize = 11.sp)
                }
            }
            Button(
                onClick = onClear,
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2564E).copy(alpha = 0.85f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("Clear", fontSize = 11.sp) }
        }
    }
}
