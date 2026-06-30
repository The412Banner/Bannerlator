package com.winlator.star.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.winlator.star.ui.theme.OnSurfaceVariant

// Shared component-spec card theme used by the Shortcuts (game) list and the Containers
// list. The card splits a container/shortcut's graphics + runtime components by how often
// you check them: renderer, DXVK and frame-gen are bright "primary" chips; driver, VKD3D
// and the x86 backend sit on a calm muted "secondary" line with a colour dot each.

// Colour-coded component chips.
internal val ChipRendColor = Color(0xFF36D1DC)
internal val ChipDriverColor = Color(0xFFFFB02E)
internal val ChipDxvkColor = Color(0xFF5BD6A6)
internal val ChipVkd3dColor = Color(0xFFC08CFF)
internal val ChipFgColor = Color(0xFFFF6FAE)
internal val ChipCpuColor = Color(0xFFFF8A5C)

// Parse a dxwrapperConfig string ("version=2.4,vkd3dVersion=2.8,...") into (DXVK, VKD3D) versions.
internal fun parseDxwrapperConfig(cfg: String): Pair<String, String> {
    val map = cfg.split(",").mapNotNull {
        val parts = it.split("=", limit = 2)
        if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
    }.toMap()
    return (map["version"] ?: "") to (map["vkd3dVersion"] ?: "")
}

// Map a renderer id (vulkan/surfaceflinger/opengl) to a display label, or "" if unknown.
internal fun rendererLabelOf(renderer: String): String = when (renderer.lowercase()) {
    "vulkan" -> "Vulkan"
    "surfaceflinger" -> "SurfaceFlinger"
    "opengl" -> "OpenGL"
    else -> ""
}

// Map a frame-gen engine id (bionic/lsfg/off) to a display label, or "" if off/unknown.
internal fun frameGenLabelOf(engine: String): String = when (engine) {
    "bionic" -> "Bionic-FG"
    "lsfg" -> "LSFG-VK"
    else -> ""
}

// The two spec rows shared by both cards: bright primary chips, then the muted secondary
// dot-line. Both rows hide themselves when they have nothing to show.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpecChipRows(
    rendererLabel: String,
    dxvkVersion: String,
    frameGenLabel: String,
    driverLabel: String,
    vkd3dVersion: String,
    backendLabel: String,
) {
    val hasPrimary = rendererLabel.isNotEmpty() || dxvkVersion.isNotEmpty() || frameGenLabel.isNotEmpty()
    if (hasPrimary) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            // Renderer + DXVK are the app's headline component chips: route them to the
            // theme accent so a preset/custom accent recolors them app-wide. Frame-gen keeps
            // its own pink identity (genuine "frame-gen active" status, not an accent surface).
            if (rendererLabel.isNotEmpty()) CompChip(rendererLabel, MaterialTheme.colorScheme.primary)
            if (dxvkVersion.isNotEmpty()) CompChip("DXVK $dxvkVersion", MaterialTheme.colorScheme.primary)
            if (frameGenLabel.isNotEmpty()) CompChip(frameGenLabel, ChipFgColor)
        }
    }
    val secondary = buildList {
        if (driverLabel.isNotEmpty()) add(driverLabel to ChipDriverColor)
        if (vkd3dVersion.isNotEmpty()) add("VKD3D $vkd3dVersion" to ChipVkd3dColor)
        if (backendLabel.isNotEmpty()) add(backendLabel to ChipCpuColor)
    }
    if (secondary.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 7.dp),
        ) {
            secondary.forEach { (text, color) -> SecondarySpec(text, color) }
        }
    }
}

// A muted secondary spec: small colour dot + dimmed label.
@Composable
internal fun SecondarySpec(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.85f)),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 10.sp,
            color = OnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// A bright primary chip: a pill with a tinted translucent background.
@Composable
internal fun CompChip(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
