package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.winlator.star.core.DirectAudioSupport
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.components.AUDIO_PRESETS
import com.winlator.star.ui.components.AudioConfig
import com.winlator.star.ui.components.AudioSettingsDialog
import com.winlator.star.ui.components.audioConfigFromEnv
import com.winlator.star.ui.components.audioConfigToEnv
import com.winlator.star.ui.components.latencyIsDirectBuffer
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import kotlinx.coroutines.CoroutineScope

// The Audio page's sound latency and microphone, which were the classic editors' audio cog
// (AudioSettingsDialog) and Microphone switch. Both live in the scope's env string, as the classic
// editors store them: BANNER_AUDIO_<ENGINE>_* for the latency (one set per sound engine, see
// AudioSettingsDialog's no-bleed contract) and BANNER_AUDIO_DIRECT_MIC=1 for the microphone. A
// container edits its env (the form's envVarsStr); a game edits its own "envVars" extra, which the
// launch merges over the container's, so a game without its own keys follows the container. The
// preset is a row; the fine-tune knobs are the shared AudioSettingsDialog, which the in-game side menu
// uses too. Wine and the Linux Steam client entry alike (WINE_LINUX), as the classic editors offer it.

internal object AudioRows {

    const val ID_ENV = "audio.env"
    const val ID_MIC = "audio.microphone"
    private const val ID_DRIVER = "audio.driver"
    private const val ENV = "envVars"
    private val ENGINES = setOf("pulseaudio", "alsa", "directaudio")

    /** The engine-scoped key prefix (AudioSettingsDialog's engTag). */
    private fun prefix(driver: String) = "BANNER_AUDIO_" + when (driver) {
        "alsa" -> "ALSA"
        "directaudio" -> "DIRECT"
        else -> "PULSE"
    } + "_"

    private fun hasEngineKeys(env: String?, driver: String) = env?.split(" ")?.any { it.startsWith(prefix(driver)) } == true

    /** [env] without this engine's keys: the game follows its container's latency again. */
    private fun withoutEngine(env: String, driver: String) = env.split(" ").filter { it.isNotBlank() && !it.startsWith(prefix(driver)) }.joinToString(" ")

    private fun vmDriver(vm: com.winlator.star.ui.screens.ContainerDetailViewModel) = StringUtils.parseIdentifier(vm.selectedAudioDriver)

    /** The sound driver a game runs: its own pick, else its container's. */
    private fun gameDriver(e: GameExtras) =
        e.get("audioDriver")?.takeIf { it.isNotEmpty() } ?: e.container.getAudioDriver() ?: ""

    /** The env a game starts with for the audio keys: its own while it has this engine's keys, else the container's. */
    fun effectiveEnv(source: SettingsSource, driver: String): String {
        val own = DefAccess(source).value(ID_ENV)
        if (source.env.scope != EditorScope.GAME || hasEngineKeys(own, driver)) return own
        return source.env.vm?.envVarsStr ?: own
    }

    /** A preset picked as AudioSettingsDialog.applyPreset does: DirectAudio keeps its own buffer. */
    private fun withPreset(env: String, driver: String, preset: String): String {
        val p = AUDIO_PRESETS.firstOrNull { it.id == preset } ?: return env
        val cfg = if (latencyIsDirectBuffer(driver)) p.cfg.copy(latencyMsec = audioConfigFromEnv(env, driver).latencyMsec) else p.cfg
        return audioConfigToEnv(env, cfg, driver)
    }

    /** DirectAudio can run here: always for the Linux entry (its own relay driver) and the defaults profile. */
    fun directAudioSupported(env: SettingsEnv) =
        env.scope == EditorScope.DEFAULTS || env.isLinuxEntry || env.vm?.let { DirectAudioSupport.isSupported(it.selectedWineVersion) } == true

    fun defs(): List<SettingDef> = listOf(
        SettingDef(
            id = "audio.latencyPreset", category = "audio", group = L("Sound latency"), level = ADVANCED,
            label = L("Latency preset"), hint = L("Auto starts low and grows the buffer only if it hears crackle. Fine-tune below."),
            tech = "BANNER_AUDIO_<engine>_PRESET",
            help = L("Each sound system keeps its own preset, so switching between PulseAudio, ALSA and DirectAudio never mixes their settings. A game without its own keeps following its container."),
            control = { _, _ -> Control.Select(AUDIO_PRESETS.map { Opt(it.id, it.name + (it.badge?.let { b -> " · $b" } ?: "")) }) },
            vm = VmBinding(
                get = { _, vm -> audioConfigFromEnv(vm.envVarsStr, vmDriver(vm)).preset },
                set = { _, vm, v -> vm.envVarsStr = withPreset(vm.envVarsStr, vmDriver(vm), v) },
            ),
            game = GameBinding(
                keys = listOf(ENV),
                read = { e ->
                    val own = e.get(ENV)
                    val driver = gameDriver(e)
                    if (hasEngineKeys(own, driver)) audioConfigFromEnv(own ?: "", driver).preset else null
                },
                write = { e, v ->
                    // Starts from the container's keys for this engine, so a preset keeps its fine-tuning where it has none.
                    val driver = gameDriver(e)
                    val own = e.get(ENV) ?: ""
                    val base = if (hasEngineKeys(own, driver)) own else audioConfigToEnv(own, audioConfigFromEnv(e.container.getEnvVars() ?: "", driver), driver)
                    e.put(ENV, withPreset(base, driver, v).ifEmpty { null })
                },
                inherited = { env ->
                    val vm = env.vm
                    val driver = env.shortcut?.getExtra("audioDriver")?.takeIf { it.isNotEmpty() } ?: vm?.let { vmDriver(it) } ?: ""
                    audioConfigFromEnv(vm?.envVarsStr ?: "", driver).preset
                },
                reset = { e -> e.get(ENV)?.let { own -> e.put(ENV, withoutEngine(own, gameDriver(e)).ifEmpty { null }) } },
            ),
            visible = { _, values -> values(ID_DRIVER) in ENGINES },
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = ID_MIC, category = "audio", group = L("Microphone"), level = ADVANCED,
            label = L("Microphone"), hint = L("Lets games use the mic: DirectAudio captures the input. Android asks for the permission once."),
            tech = DirectAudioSupport.MIC_ENV_KEY,
            control = { _, _ -> Control.Toggle },
            vm = VmBinding(
                get = { _, vm -> if (DirectAudioSupport.isMicEnabledInEnv(vm.envVarsStr)) "1" else "0" },
                set = { _, vm, v -> vm.envVarsStr = DirectAudioSupport.withMicEnabled(vm.envVarsStr, v == "1") },
            ),
            // A game adds the flag to its own env; without it the game follows its container.
            game = GameBinding(
                keys = listOf(ENV),
                read = { e -> if (DirectAudioSupport.isMicEnabledInEnv(e.get(ENV))) "1" else null },
                write = { e, v -> e.put(ENV, DirectAudioSupport.withMicEnabled(e.get(ENV), v == "1").ifEmpty { null }) },
                reset = { e -> e.get(ENV)?.let { own -> e.put(ENV, DirectAudioSupport.withMicEnabled(own, false).ifEmpty { null }) } },
            ),
            disabled = { env, values ->
                when {
                    values(ID_DRIVER) != "directaudio" || !directAudioSupported(env) -> L("Available on the DirectAudio sound system.")
                    // The launch merges the env strings, so a game can't take away the container's flag.
                    env.scope == EditorScope.GAME && env.vm?.let { DirectAudioSupport.isMicEnabledInEnv(it.envVarsStr) } == true ->
                        L("On for the whole container: switch it off in the container.")
                    else -> null
                }
            },
            worlds = WINE_LINUX,
        ),
        // The env string the fine-tune dialog edits: the container's, or the game's own (never its container's).
        hiddenDef(
            ID_ENV, "audio",
            vm = VmBinding(get = { _, vm -> vm.envVarsStr }, set = { _, vm, v -> vm.envVarsStr = v }),
            game = ownExtra(ENV),
            worlds = WINE_LINUX,
        ),
    )
}

/** The Audio page's fine-tune action and the microphone permission, for a game, a container and All containers. */
internal class DeckAudioSection(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) : DeckSection {
    private val access = DefAccess(source)
    var fineTune by mutableStateOf(false)

    private fun driver() = access.value("audio.driver")

    override fun extras(): Map<String, CategoryExtra> {
        if (env.scope == EditorScope.APP || env.world == World.ANDROID || access.def(AudioRows.ID_ENV) == null) return emptyMap()
        return mapOf("audio" to CategoryExtra(bottom = ::bottomItems))
    }

    private fun bottomItems(): List<PageExtra> {
        val driver = driver()
        if (driver !in setOf("pulseaudio", "alsa", "directaudio")) return emptyList()
        val cfg = audioConfigFromEnv(AudioRows.effectiveEnv(source, driver), driver)
        val preset = AUDIO_PRESETS.firstOrNull { it.id == cfg.preset }?.name ?: cfg.preset
        return actionItems("audio-tune", null, listOf(
            DeckAction("tune", Icons.Filled.Tune, L("Fine-tune the sound buffer"),
                "$preset · ${driverLabel(driver)}. Buffer size, performance mode and adaptive growth.",
                enabled = access.editable(AudioRows.ID_ENV)) { fineTune = true },
        ))
    }

    private fun driverLabel(driver: String) = when (driver) {
        "alsa" -> "ALSA"
        "pulseaudio" -> L("PulseAudio")
        "directaudio" -> L("DirectAudio")
        else -> driver
    }

    private fun save(cfg: AudioConfig) {
        fineTune = false
        val driver = driver()
        access.set(AudioRows.ID_ENV, audioConfigToEnv(access.value(AudioRows.ID_ENV), cfg, driver))
    }

    @Composable
    override fun Dialogs() {
        if (extras().isEmpty()) return
        val context = env.context
        // Turning the microphone on asks Android for the permission; a refusal turns it off again, as the classic switch does.
        val mic = access.value(AudioRows.ID_MIC)
        var lastMic by remember { mutableStateOf(mic) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                access.set(AudioRows.ID_MIC, "0")
                Toast.makeText(context, L("Microphone permission denied: the mic stays off."), Toast.LENGTH_SHORT).show()
            }
        }
        LaunchedEffect(mic) {
            val turnedOn = mic == "1" && lastMic != "1"
            lastMic = mic
            if (turnedOn && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (fineTune) {
            val driver = driver()
            AudioSettingsDialog(
                initial = audioConfigFromEnv(AudioRows.effectiveEnv(source, driver), driver),
                scopeLabel = when (env.scope) {
                    EditorScope.GAME -> L("this game")
                    EditorScope.DEFAULTS -> L("new containers")
                    else -> L("this container")
                },
                latencyLive = true,
                driverLabel = driverLabel(driver),
                driverId = driver,
                onDismiss = { fineTune = false },
                onSave = { save(it) },
            )
        }
    }
}
