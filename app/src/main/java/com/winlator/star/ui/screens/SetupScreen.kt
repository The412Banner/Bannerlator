package com.winlator.star.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.contents.ContentsManager
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class ProtonOption(
    val label: String,
    val wineVersion: String,
    val isRecommended: Boolean = false,
)

private val PROTON_OPTIONS = listOf(
    ProtonOption("Proton 9 arm64ec", "proton-9.0-arm64ec", isRecommended = true),
    ProtonOption("Proton 9 x86_64", "proton-9.0-x86_64"),
)

private fun createDefaultContainerData(name: String, wineVersion: String): JSONObject {
    return JSONObject().apply {
        put("name", name)
        put("screenSize", Container.DEFAULT_SCREEN_SIZE)
        put("envVars", Container.DEFAULT_ENV_VARS)
        put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER)
        put("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG)
        put("dxwrapper", Container.DEFAULT_DXWRAPPER)
        put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG)
        put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
        put("emulator", Container.DEFAULT_EMULATOR)
        put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
        put("drives", Container.DEFAULT_DRIVES)
        put("showFPS", false)
        put("fullscreenStretched", false)
        put("exclusiveXInput", false)
        put("lsfgEnabled", Container.DEFAULT_LSFG_ENABLED)
        put("lsfgMultiplier", Container.DEFAULT_LSFG_MULTIPLIER)
        put("lsfgQuality", Container.DEFAULT_LSFG_QUALITY)
        put("lsfgFlowScale", Container.DEFAULT_LSFG_FLOW_SCALE)
        put("lsfgMaxLatency", Container.DEFAULT_LSFG_MAX_LATENCY)
        put("lsfgGpuArch", Container.DEFAULT_LSFG_GPU_ARCH)
        put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL.toInt())
        put("wineVersion", wineVersion)
        put("box64Preset", "intermediate")
        put("fexcorePreset", "intermediate")
        put("desktopTheme", "Star,default,#000000")
        put("inputType", 0)
        put("lc_all", "")
        put("midiSoundFont", "")
    }
}

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    val checkedOptions = remember { mutableStateListOf<Boolean>().also { it.addAll(List(PROTON_OPTIONS.size) { false }) } }
    var isCreating by remember { mutableStateOf(false) }
    var containersCreated by remember { mutableStateOf(false) }
    var creationError by remember { mutableStateOf<String?>(null) }
    var completedCount by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(48.dp + 24.dp))

            // ── Logo + "Configuration" gradient text ─────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Configuration",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF6A4FC4), Color(0xFF8B6BE0), Color(0xFFB49BF5)),
                        ),
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Create your first containers to start gaming.",
                fontSize = 14.sp,
                color = Color(0xFF888888),
            )

            Spacer(Modifier.height(36.dp))

            // ── Create starting containers section ──────────────────────
            Button(
                onClick = { expanded = !expanded },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B6BE0)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    text = if (expanded) "Cancel" else "Create starting containers",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Select Proton versions:",
                        fontSize = 13.sp,
                        color = Color(0xFFAAAAAA),
                    )

                    PROTON_OPTIONS.forEachIndexed { index, option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checkedOptions[index] = !checkedOptions[index]
                                }
                                .background(
                                    if (checkedOptions[index]) Color(0xFF2A1F4E) else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Checkbox(
                                checked = checkedOptions[index],
                                onCheckedChange = { checkedOptions[index] = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF8B6BE0),
                                    uncheckedColor = Color(0xFF666666),
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = option.label,
                                        fontSize = 14.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (option.isRecommended) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "(Recommended)",
                                            fontSize = 11.sp,
                                            color = Color(0xFF8B6BE0),
                                        )
                                    }
                                }
                                Text(
                                    text = option.wineVersion,
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = {
                            val selected = PROTON_OPTIONS.filterIndexed { i, _ -> checkedOptions[i] }
                            if (selected.isEmpty()) return@Button
                            isCreating = true
                            creationError = null
                            completedCount = 0

                            scope.launch {
                                val cm = ContainerManager(context)
                                val contentsManager = ContentsManager(context)
                                contentsManager.syncContents()

                                for (option in selected) {
                                    val data = createDefaultContainerData(option.label, option.wineVersion)
                                    cm.createContainerAsync(data, contentsManager) { created ->
                                        if (created != null) {
                                            completedCount++
                                            if (completedCount == selected.size) {
                                                isCreating = false
                                                containersCreated = true
                                            }
                                        } else {
                                            isCreating = false
                                            creationError = "Failed to create ${option.label}"
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isCreating && checkedOptions.any { it },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6A4FC4),
                            disabledContainerColor = Color(0xFF333333),
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Creating\u2026", color = Color.White, fontSize = 14.sp)
                        } else {
                            Text("Create", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }

                    creationError?.let {
                        Text(
                            text = it,
                            color = Color(0xFFFF6B6B),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }

                    if (containersCreated) {
                        Text(
                            text = "\u2713 Container${
                                if (completedCount > 1) "s" else ""
                            } created successfully",
                            color = Color(0xFF6BCB6B),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(48.dp))

            // ── Next button ─────────────────────────────────────────────
            Button(
                onClick = {
                    PreferenceManager.getDefaultSharedPreferences(context).edit {
                        putBoolean("setup_completed", true)
                    }
                    onSetupComplete()
                },
                enabled = containersCreated,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6A4FC4),
                    disabledContainerColor = Color(0xFF333333),
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    text = "Next",
                    color = if (containersCreated) Color.White else Color(0xFF888888),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
