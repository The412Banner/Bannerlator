package com.winlator.star.ui.deck.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WebAsset
import com.winlator.star.container.Container
import com.winlator.star.core.DirectAudioSupport
import com.winlator.star.core.StringUtils
import com.winlator.star.core.UnrealHdr
import com.winlator.star.display.WaylandHdr
import com.winlator.star.midi.MidiManager
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import com.winlator.star.ui.deck.settings.SettingLevel.EXPERT
import com.winlator.star.ui.deck.settings.SettingLevel.SIMPLE
import com.winlator.star.ui.screens.ContainerDetailViewModel
import java.util.Locale

// Every setting the Deck editor edits itself, for a game, a container and All containers. Labels,
// hints and grouping follow the Deck design (proto/data/settings.js). The storage is today's: the
// container form writes exactly what the classic container editor writes, and a game writes the same
// shortcut extras the classic game editor writes, but only the ones the user actually changed.
//
// Per-game rows are only offered where the launch path reads the shortcut extra with the container as
// the fallback (XServerDisplayActivity, GuestProgramLauncherComponent). A container field the launch
// never reads per game (MIDI, the overlay switch, gyro deadzone and smoothing, LSFG performance mode)
// is shown read-only in a game's editor as a container setting.

internal object SettingsRegistry {

    val categories: List<SettingsCategory> = listOf(
        SettingsCategory("game", "Game", "Name and launch options for this one game.", Icons.Filled.SportsEsports),
        SettingsCategory("launch", "Launch", "How this game starts: Steam or not, controller hand-off, anti-cheat.", Icons.Filled.RocketLaunch),
        SettingsCategory("display", "Display", "Resolution, how the picture fills the screen, refresh rate and HDR.", Icons.Filled.Monitor),
        SettingsCategory("graphics", "Graphics", "DirectX translation, GPU driver and how the picture is put on screen.", Icons.Filled.Layers),
        SettingsCategory("performance", "Performance", "Frame rate limit, frame generation, x86 translator, CPU cores, background services.", Icons.Filled.Speed),
        SettingsCategory("visuals", "Visual effects", "Sharpening and other post effects.", Icons.Filled.AutoAwesome),
        SettingsCategory("audio", "Audio", "Sound driver and MIDI music.", Icons.AutoMirrored.Filled.VolumeUp),
        SettingsCategory("input", "Controls", "Controllers, on-screen controls, mouse and touch, rumble and motion aim.", Icons.Filled.Gamepad),
        SettingsCategory("hud", "Performance overlay", "The on-screen FPS and temperature readout.", Icons.Filled.MonitorHeart),
        SettingsCategory("windows", "Windows", "Container name, language, desktop and session behaviour.", Icons.Filled.WebAsset),
        SettingsCategory("linux", "Linux runtime", "Only for the Linux Steam client entry: its drivers, Steam client options and component swaps.", Icons.Filled.Terminal),
        SettingsCategory("expert", "Expert", "Raw environment variables and translator variables.", Icons.Filled.Build),
    )

    val defs: List<SettingDef> by lazy { buildDefs() }

    /** Rows that open the classic editor for what is not edited here. [target] "classic" is resolved by the host. */
    fun handoffs(scope: EditorScope, env: SettingsEnv): Map<String, List<Handoff>> {
        val game = scope == EditorScope.GAME
        val where = if (game) "the classic game editor" else "the classic container editor"
        fun h(label: String, hint: String) = Handoff(label, "$hint Opens $where.", "classic")
        val m = linkedMapOf<String, List<Handoff>>()
        if (game) {
            m["game"] = listOf(h("Icon, program to run and storage", "Pick an icon, change the .exe or move the game to drive C (General tab)."))
            m["launch"] = listOf(Handoff("Offline Steam mode, file checks and updates", "Goldberg mode, Verify files and Check for updates live in the launch menu. Turn off \"Skip the launch menu\" to see it on the next start.", "none"))
        }
        m["display"] = listOfNotNull(
            if (game) h("TV and external display", "Start on the TV, TV picture mode, match the TV resolution (TV tab).") else null,
            h("Wayland game driver", "Which Vulkan driver the game renders with on Wayland (General tab)."),
        )
        m["graphics"] = listOf(
            h("DXVK, VKD3D and VEGAS versions", "Versions, async shaders, feature level and the VEGAS config (DX Wrapper Config)."),
            h("Driver version and tuning", "Turnip / Adreno version, Vulkan version, extensions, GPU name, memory limit, BCn textures."),
        )
        m["performance"] = listOf(
            h("Services to start and translator presets", "The Custom services list, and adding or editing Box64 and FEX presets (Advanced tab)."),
        ) + if (game) listOf(h("Power and root tweaks", "Per-game power profile, GPU clock lock and root switches (Performance).")) else emptyList()
        m["visuals"] = listOf(h("ReShade effects", "Pick, order and tune ReShade effects (Advanced tab)."))
        m["audio"] = listOf(h("Sound latency and microphone", "The latency preset, fine-tune values and the DirectAudio microphone (Audio settings)."))
        m["input"] = listOf(h("Player slot pins", "Which physical controller is which player (Controller tab)."))
        if (!game) m["hud"] = listOf(h("Overlay style and metrics", "Style, size, metrics and temperature alerts. Also changeable in-game from the side menu."))
        m["windows"] = listOfNotNull(
            h("Windows components", "Builtin or native DLLs for DirectX, audio and runtimes (Win Components tab)."),
            if (!game) h("Drives", "Drive letters D: to Z: and the folders behind them. Duplicate letters are checked there (Drives tab).") else null,
            if (!game) h("Wine / Proton version and wallpaper", "The layer is chosen when the container is created; the wallpaper and colours are on the Wine Config tab.") else null,
        )
        // The Linux runtime options live on the Linux Steam client entry only; containers have none.
        if (game && env.isLinuxEntry) {
            m["linux"] = listOf(h("Linux runtime options", "Draw driver, Steam client update channel, gamescope scaling, speed tweaks, troubleshooting and component swaps."))
        }
        m["expert"] = listOf(
            if (game) h("Environment variables", "Raw environment variables, merged over the container's, and game-folder DLL overrides (Env Vars tab).")
            else h("Environment variables", "Raw environment variables every game in this container starts with (Env Vars tab)."),
        )
        return m
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun onOff(b: Boolean) = if (b) "1" else "0"
    private fun idOf(s: String): String = StringUtils.parseIdentifier(s)
    private fun displayFor(id: String, entries: List<String>): String =
        entries.firstOrNull { idOf(it) == id } ?: entries.firstOrNull() ?: id
    private fun fmt1(f: Float) = String.format(Locale.ENGLISH, "%.1f", f)
    private fun fmt2(f: Float) = String.format(Locale.ENGLISH, "%.2f", f)

    /** A plain per-game extra: absent or empty means the game follows the container. */
    private fun key(k: String, normalize: (String) -> String? = { it }) = GameBinding(
        keys = listOf(k),
        read = { e -> e.get(k)?.takeIf { it.isNotEmpty() }?.let(normalize) },
        write = { e, v -> e.put(k, v) },
    )

    /** A per-game on/off extra stored as "1" / "0". */
    private fun key10(k: String) = key(k) { if (it == "1") "1" else "0" }

    /** A per-game on/off extra stored as "true" / "false" (the Vulkan renderer options). */
    private fun keyTF(k: String) = GameBinding(
        keys = listOf(k),
        read = { e -> e.get(k)?.takeIf { it.isNotEmpty() }?.let { if (it == "true") "1" else "0" } },
        write = { e, v -> e.put(k, if (v == "1") "true" else "false") },
    )

    private fun intKey(k: String) = key(k) { it.trim().toIntOrNull()?.toString() }

    private fun vmBool(get: (ContainerDetailViewModel) -> Boolean, set: (ContainerDetailViewModel, Boolean) -> Unit) =
        VmBinding(get = { _, vm -> onOff(get(vm)) }, set = { _, vm, v -> set(vm, v == "1") })

    private fun vmInt(get: (ContainerDetailViewModel) -> Int, set: (ContainerDetailViewModel, Int) -> Unit) =
        VmBinding(get = { _, vm -> get(vm).toString() }, set = { _, vm, v -> v.toIntOrNull()?.let { set(vm, it) } })

    private fun vmString(get: (ContainerDetailViewModel) -> String, set: (ContainerDetailViewModel, String) -> Unit) =
        VmBinding(get = { _, vm -> get(vm) }, set = { _, vm, v -> set(vm, v) })

    private fun seg(vararg o: Pair<String, String>) = Control.Segmented(o.map { Opt(it.first, it.second) })

    /** "1280x720 (16:9)" → "1280 × 720 (16:9)". */
    fun prettyResolution(entry: String): String = entry.replaceFirst(Regex("""^(\d+)x(\d+)"""), "$1 × $2")

    private val RESOLUTION = Regex("""^\s*(\d{2,5})\s*[xX×]\s*(\d{2,5})\s*$""")

    private fun validateResolution(text: String): String? {
        val m = RESOLUTION.find(text) ?: return "Type it as width x height, e.g. 1600x900."
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        if (w % 2 != 0 || h % 2 != 0) return "Both numbers must be even."
        if (w < 320 || h < 200) return "That is too small for a Windows desktop."
        if (w > 7680 || h > 4320) return "That is larger than 8K."
        return null
    }

    private fun normalizeResolution(text: String): String =
        RESOLUTION.find(text)?.let { "${it.groupValues[1]}x${it.groupValues[2]}" } ?: text.trim()

    private val LOCALE = Regex("""^[a-z]{2,3}_[A-Z]{2}(\.[A-Za-z0-9-]+)?$""")

    private fun x11(env: SettingsEnv, values: (String) -> String) = !env.isLinuxEntry && !env.wayland(values)
    private fun vulkanRenderer(env: SettingsEnv, values: (String) -> String) = x11(env, values) && values("graphics.renderer") == "vulkan"

    // ── The settings ────────────────────────────────────────────────────────────────────────────

    private fun buildDefs(): List<SettingDef> = listOf(

        // Game (per game only)
        SettingDef(
            id = "game.name", category = "game", group = "Name", level = SIMPLE,
            label = "Name", hint = "Shown in your library. Renaming keeps the cover and icon.", tech = "Name / Game Name",
            control = { _, _ -> Control.TextField("Game name") { t ->
                when {
                    t.isBlank() -> "The name can't be empty."
                    t.any { it in "\\/:*?\"<>|" } -> "A name can't contain \\ / : * ? \" < > |"
                    else -> null
                }
            } },
            game = GameBinding(listOf(GameSource.NAME_KEY), read = { e -> e.get(GameSource.NAME_KEY) }, write = { e, v -> e.put(GameSource.NAME_KEY, v) }, inherit = Inherit.NONE),
            visible = { env, _ -> env.scope == EditorScope.GAME },
        ),
        SettingDef(
            id = "game.arguments", category = "game", group = "Name", level = ADVANCED,
            label = "Launch options", hint = "Extra words added after the program name, e.g. -windowed -dx11.", tech = "Exec Arguments",
            control = { _, _ -> Control.TextField("-windowed") },
            game = GameBinding(listOf("execArgs"), read = { e -> e.get("execArgs") ?: "" }, write = { e, v -> e.put("execArgs", v.ifBlank { null }) }, inherit = Inherit.NONE),
            visible = { env, _ -> env.scope == EditorScope.GAME && !env.isLinuxEntry },
        ),

        // Launch (per game only)
        SettingDef(
            id = "launch.method", category = "launch", group = "How it starts", level = SIMPLE,
            label = "Start the game with", tech = "Launch with: SteamLite / Goldberg / Raw .exe",
            help = "Steam runs the real Steam client (SteamLite) so achievements, cloud saves and online play work. Offline Steam (Goldberg) pretends to be Steam without signing in. Just the .exe starts the game's own program.",
            control = { _, _ -> seg("RealSteam" to "Steam", "Goldberg" to "Offline Steam", "Raw" to "Just the .exe") },
            game = GameBinding(
                listOf("launchMode"),
                read = { e -> e.get("launchMode")?.takeIf { it == "RealSteam" || it == "Goldberg" || it == "Raw" } },
                write = { e, v -> e.put("launchMode", v) },
                inherit = Inherit.BUILT_IN, inherited = { "RealSteam" },
            ),
            visible = { env, _ -> env.scope == EditorScope.GAME && env.isSteam },
        ),
        SettingDef(
            id = "launch.rememberChoice", category = "launch", group = "How it starts", level = SIMPLE,
            label = "Skip the launch menu", hint = "Start straight away with the saved choices. Turn off to see the launch menu again.", tech = "Remember my choice",
            control = { _, _ -> Control.Toggle },
            game = GameBinding(
                listOf("launchModeRemembered"),
                read = { e -> e.get("launchModeRemembered")?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
                write = { e, v -> e.put("launchModeRemembered", v) },
                inherit = Inherit.BUILT_IN, inherited = { "0" },
            ),
            visible = { env, _ -> env.scope == EditorScope.GAME && !env.isLinuxEntry },
        ),
        SettingDef(
            id = "launch.controllerPassthrough", category = "launch", group = "Steam options", level = ADVANCED,
            label = "Let Steam handle the controller", hint = "Passes your pad straight to Steam Input instead of the app's own mapping.", tech = "Controller passthrough",
            control = { _, _ -> Control.Toggle },
            game = key10("controllerPassthrough").let { GameBinding(it.keys, it.read, it.write, Inherit.BUILT_IN, inherited = { "0" }) },
            visible = { env, values -> env.scope == EditorScope.GAME && env.isSteam && values("launch.method") == "RealSteam" },
        ),
        SettingDef(
            id = "launch.vacSecure", category = "launch", group = "Steam options", level = ADVANCED,
            label = "Anti-cheat (VAC) secure launch", hint = "Needed to join VAC-protected servers. Detect follows Steam's app info.", tech = "Requires secure (VAC) launch",
            control = { _, _ -> seg("auto" to "Detect", "1" to "Always", "0" to "Never") },
            game = GameBinding(
                listOf("steamVacLaunch"),
                read = { e -> e.get("steamVacLaunch")?.trim()?.takeIf { it == "1" || it == "0" } },
                write = { e, v -> e.put("steamVacLaunch", if (v == "auto") null else v) },
                inherit = Inherit.BUILT_IN, inherited = { "auto" },
            ),
            visible = { env, values -> env.scope == EditorScope.GAME && env.isSteam && values("launch.method") == "RealSteam" },
        ),

        // Display
        SettingDef(
            id = "display.resolution", category = "display", group = "Screen", level = SIMPLE,
            label = "Game resolution", hint = "The size of the Windows desktop the game sees. Lower = faster.", tech = "Screen Size",
            help = "Games render at this size, then the picture is scaled to your screen. A lower resolution is faster and cooler; a higher one is sharper.",
            control = { env, _ ->
                Control.Select(
                    options = (env.vm?.screenSizeEntries ?: emptyList())
                        .filterNot { it.equals("custom", ignoreCase = true) }
                        .map { Opt(idOf(it), prettyResolution(it)) },
                    customLabel = "Custom…",
                    customHint = "Width x height, both even, e.g. 1600x900",
                    validateCustom = { validateResolution(it) },
                    normalizeCustom = { normalizeResolution(it) },
                )
            },
            vm = VmBinding(
                get = { _, vm ->
                    if (vm.selectedScreenSize.equals("custom", ignoreCase = true)) "${vm.customWidth.trim()}x${vm.customHeight.trim()}"
                    else idOf(vm.selectedScreenSize)
                },
                set = { _, vm, v ->
                    if (vm.screenSizeEntries.any { !it.equals("custom", ignoreCase = true) && idOf(it) == v }) {
                        vm.selectedScreenSize = v
                    } else {
                        vm.selectedScreenSize = "custom"
                        vm.customWidth = v.substringBefore('x')
                        vm.customHeight = v.substringAfter('x', "")
                    }
                },
            ),
            game = key("screenSize"),
        ),
        SettingDef(
            id = "display.fullscreenMode", category = "display", group = "Screen", level = SIMPLE,
            label = "Fill the screen", tech = "Fullscreen Mode",
            control = { _, _ ->
                Control.Chips(listOf(
                    Opt("${Container.FULLSCREEN_OFF}", "Windowed"),
                    Opt("${Container.FULLSCREEN_FIT}", "Fit (black bars)"),
                    Opt("${Container.FULLSCREEN_STRETCH}", "Stretch"),
                    Opt("${Container.FULLSCREEN_FILL}", "Fill (crop)"),
                    Opt("${Container.FULLSCREEN_INTEGER}", "Pixel-perfect"),
                ))
            },
            vm = vmInt({ it.fullscreenMode }, { vm, v -> vm.fullscreenMode = v }),
            // The launch path still honours the legacy per-game "fullscreenStretched" when no mode is stored, so it is part of this setting.
            game = GameBinding(
                keys = listOf("fullscreenMode", "fullscreenStretched"),
                read = { e ->
                    val mode = e.get("fullscreenMode")?.trim()?.takeIf { it.isNotEmpty() }
                    val legacy = e.get("fullscreenStretched")?.takeIf { it.isNotEmpty() }
                    mode?.toIntOrNull()?.toString()
                        ?: legacy?.let { if (it == "1") "${Container.FULLSCREEN_STRETCH}" else "${Container.FULLSCREEN_OFF}" }
                },
                write = { e, v -> e.put("fullscreenMode", v); e.put("fullscreenStretched", null) },
            ),
        ),
        SettingDef(
            id = "display.alignment", category = "display", group = "Screen", level = ADVANCED,
            label = "Picture position", hint = "Useful on foldables: keep the game on one half.", tech = "Screen Alignment",
            control = { _, _ -> seg("${Container.ALIGN_CENTER}" to "Centre", "${Container.ALIGN_TOP}" to "Top", "${Container.ALIGN_BOTTOM}" to "Bottom") },
            vm = vmInt({ it.screenAlignment }, { vm, v -> vm.screenAlignment = v }),
            game = intKey("screenAlignment"),
        ),
        SettingDef(
            id = SettingsEnv.ID_BACKEND, category = "display", group = "Screen", level = ADVANCED,
            label = "Display system", hint = "Wayland needs a Proton layer built for it.", tech = "Display backend",
            help = "X11 is the classic path and works everywhere. Wayland hands game frames to the screen more directly on the layers built for it.",
            control = { env, _ ->
                Control.Segmented(listOf(
                    Opt(Container.DISPLAY_BACKEND_X11, "X11"),
                    Opt(Container.DISPLAY_BACKEND_WAYLAND, "Wayland",
                        disabledReason = if (env.waylandCapable) null else "This container's Wine layer can't drive Wayland."),
                ))
            },
            vm = vmString({ it.displayBackend }, { vm, v -> vm.onDisplayBackendChanged(v) }),
            game = key("displayBackend") { if (it == Container.DISPLAY_BACKEND_WAYLAND) it else Container.DISPLAY_BACKEND_X11 },
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        SettingDef(
            id = "display.renderScale", category = "display", group = "Screen", level = ADVANCED,
            label = "Supersampling", hint = "Renders bigger, then shrinks for a cleaner image. Costs performance.", tech = "Render scale (supersampling)",
            control = { _, _ -> seg("1.0" to "Off", "1.25" to "1.25×", "1.5" to "1.5×", "2.0" to "2×") },
            vm = vmString({ it.renderScale }, { vm, v -> vm.renderScale = v }),
            game = key("renderScale") { v -> v.toFloatOrNull()?.let { f -> listOf("1.0", "1.25", "1.5", "2.0").firstOrNull { it.toFloat() == f } } },
            visible = { env, values -> x11(env, values) },
        ),
        SettingDef(
            id = "display.refreshInGame", category = "display", group = "Refresh rate", level = ADVANCED,
            label = "Refresh rate the game can pick", hint = "What the game is told the screen can do. Needs a Wine build with xrandr.", tech = "In-game refresh rate",
            control = { env, _ ->
                Control.Select(listOf(Opt("-1", "Locked to 60 Hz"), Opt("0", "Any (unlimited)")) +
                    env.panelRates.filter { it > 60 }.map { Opt("$it", "$it Hz") })
            },
            vm = VmBinding(
                get = { _, vm -> if (!vm.unlockGameRefreshRate) "-1" else vm.maxGameRefreshRate.toString() },
                set = { _, vm, v ->
                    if (v == "-1") { vm.unlockGameRefreshRate = false; vm.maxGameRefreshRate = 0 }
                    else { vm.unlockGameRefreshRate = true; vm.maxGameRefreshRate = v.toIntOrNull() ?: 0 }
                },
            ),
            game = GameBinding(
                keys = listOf("unlockGameRefreshRate", "maxGameRefreshRate"),
                read = { e ->
                    val unlock = e.get("unlockGameRefreshRate")?.takeIf { it.isNotEmpty() }
                    val max = e.get("maxGameRefreshRate")?.takeIf { it.isNotEmpty() }
                    when {
                        unlock == null && max == null -> null
                        unlock == "0" -> "-1"
                        else -> (max?.toIntOrNull() ?: 0).toString()
                    }
                },
                write = { e, v ->
                    if (v == "-1") { e.put("unlockGameRefreshRate", "0"); e.put("maxGameRefreshRate", "0") }
                    else { e.put("unlockGameRefreshRate", "1"); e.put("maxGameRefreshRate", v) }
                },
            ),
            visible = { env, _ -> !env.isLinuxEntry && env.panelRates.isNotEmpty() },
        ),
        SettingDef(
            id = "display.refreshAuto", category = "display", group = "Refresh rate", level = ADVANCED,
            label = "Match screen refresh to the game's FPS", hint = "Smoother motion and less battery on screens with variable refresh.", tech = "Auto (match FPS)",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.matchRefreshRate }, { vm, v -> vm.matchRefreshRate = v }),
            game = key10("matchRefreshRate"),
            disabled = { env, _ -> if (env.vrrCapable) null else "This screen can't change its refresh rate." },
        ),
        SettingDef(
            id = "display.refreshManual", category = "display", group = "Refresh rate", level = ADVANCED,
            label = "Fixed screen refresh", hint = "Used while matching is off.", tech = "Manual refresh rate",
            control = { env, _ -> Control.Chips(listOf(Opt("0", "Off")) + env.panelRates.map { Opt("$it", "$it Hz") }) },
            vm = vmInt({ it.manualRefreshRate }, { vm, v -> vm.manualRefreshRate = v }),
            game = intKey("manualRefreshRate"),
            visible = { env, values -> env.vrrCapable && env.panelRates.isNotEmpty() && values("display.refreshAuto") == "0" },
        ),
        SettingDef(
            id = "display.hdrOutput", category = "display", group = "HDR", level = ADVANCED,
            label = "HDR output", hint = "Sends real HDR10 to the screen.", tech = "HDR output (HDR10)",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.waylandHdr }, { vm, v -> vm.waylandHdr = v }),
            game = key10(WaylandHdr.EXTRA),
            visible = { env, values -> env.wayland(values) },
            disabled = { env, _ -> runCatching { WaylandHdr.unavailableReason(env.context) }.getOrNull() },
        ),

        // Graphics
        SettingDef(
            id = "graphics.dxWrapper", category = "graphics", group = "DirectX translation", level = SIMPLE,
            label = "DirectX translation", hint = "How Windows DirectX calls are turned into Vulkan for your GPU.", tech = "DX Wrapper",
            help = "DXVK + VKD3D suits most DirectX 9–12 games. VEGAS is a tuned DXVK fork. WineD3D is the slow but broad fallback for very old games.",
            control = { env, _ ->
                val labels = mapOf("dxvk+vkd3d" to "DXVK + VKD3D", "vegas+vkd3d" to "VEGAS + VKD3D", "wined3d" to "WineD3D")
                val ids = (env.vm?.dxWrapperEntries ?: emptyList()).map { idOf(it) }
                val order = listOf("dxvk+vkd3d", "vegas+vkd3d", "wined3d")
                Control.Segmented(ids.sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }.map { Opt(it, labels[it] ?: it) })
            },
            vm = VmBinding(get = { _, vm -> idOf(vm.selectedDXWrapper) }, set = { _, vm, v -> vm.selectedDXWrapper = displayFor(v, vm.dxWrapperEntries) }),
            game = key("dxwrapper"),
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        SettingDef(
            id = "graphics.unrealHdr", category = "graphics", group = "Unreal Engine", level = ADVANCED,
            label = "Unreal Engine HDR fix", hint = "The DirectX 11 fix pretends to be an NVIDIA GPU.", tech = "Unreal Engine HDR",
            help = UnrealHdr.HELP_SHORT,
            control = { _, _ -> seg(UnrealHdr.OFF to "Off", UnrealHdr.DX12 to "DirectX 12 fix", UnrealHdr.DX11 to "DirectX 11 (NVAPI)") },
            vm = vmString({ it.unrealHdr }, { vm, v -> vm.unrealHdr = v }),
            game = key(UnrealHdr.EXTRA) { UnrealHdr.normalize(it).ifEmpty { null } },
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        SettingDef(
            id = "graphics.driver", category = "graphics", group = "GPU driver", level = SIMPLE,
            label = "Graphics driver", hint = "The Vulkan wrapper games use on X11. Imported wrappers appear here too.", tech = "Graphics Driver",
            control = { env, _ -> Control.Select((env.vm?.graphicsDriverEntries ?: emptyList()).map { Opt(idOf(it), it) }) },
            vm = VmBinding(get = { _, vm -> idOf(vm.selectedGraphicsDriver) }, set = { _, vm, v -> vm.selectedGraphicsDriver = displayFor(v, vm.graphicsDriverEntries) }),
            game = key("graphicsDriver"),
            visible = { env, values -> x11(env, values) },
        ),
        SettingDef(
            id = "graphics.renderer", category = "graphics", group = "How the picture reaches the screen", level = ADVANCED,
            label = "Screen renderer", hint = "Draws the Windows desktop onto Android. Wayland always uses Vulkan.", tech = "Renderer",
            control = { _, _ -> seg("opengl" to "OpenGL", "vulkan" to "Vulkan", "surfaceflinger" to "SurfaceFlinger") },
            vm = VmBinding(get = { _, vm -> idOf(vm.selectedRenderer) }, set = { _, vm, v -> vm.selectedRenderer = displayFor(v, RENDERERS) }),
            game = key("renderer"),
            visible = { env, values -> x11(env, values) },
            confirm = { v ->
                if (v == "surfaceflinger") Confirm(
                    "SurfaceFlinger renderer — Experimental",
                    "SurfaceFlinger hands game frames straight to Android's compositor. It can be faster, but on some devices it shows a black screen or wrong colours. Switch back to Vulkan or OpenGL if that happens.",
                    "Use it anyway",
                ) else null
            },
        ),
        SettingDef(
            id = "graphics.sfColours", category = "graphics", group = "How the picture reaches the screen", level = ADVANCED,
            label = "Fix swapped colours", hint = "Swaps red and blue back (BGRA to RGBA).", tech = "Correct SurfaceFlinger colours",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.rendererSfCompatMode }, { vm, v -> vm.rendererSfCompatMode = v }),
            game = key10("sfCompatMode"),
            visible = { env, values -> x11(env, values) && values("graphics.renderer") == "surfaceflinger" },
        ),
        SettingDef(
            id = "graphics.nativeRenderer", category = "graphics", group = "How the picture reaches the screen", level = ADVANCED,
            label = "Direct to screen (native renderer)", hint = "Skips the compositor for lower lag. Turns off screen effects.", tech = "Native Renderer",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.rendererNative }, { vm, v -> vm.rendererNative = v }),
            game = keyTF("native"),
            visible = { env, values -> vulkanRenderer(env, values) },
        ),
        SettingDef(
            id = "graphics.nativeBackend", category = "graphics", group = "How the picture reaches the screen", level = EXPERT,
            label = "Direct-to-screen method", tech = "Native backend",
            control = { _, _ -> seg("auto" to "Auto", "asr" to "SurfaceFlinger", "flip" to "Vulkan scan-out") },
            vm = vmString({ it.rendererNativeBackend }, { vm, v -> vm.rendererNativeBackend = v }),
            game = key("nativeBackend"),
            visible = { env, values -> vulkanRenderer(env, values) && values("graphics.nativeRenderer") == "1" },
        ),
        SettingDef(
            id = "graphics.presentMode", category = "graphics", group = "How the picture reaches the screen", level = ADVANCED,
            label = "Frame presentation", hint = "Locked to Fast V-Sync while frame generation runs.", tech = "Present Mode",
            control = { _, _ -> seg("fifo" to "V-Sync", "mailbox" to "Fast V-Sync", "immediate" to "Off") },
            vm = vmString({ it.rendererPresentMode }, { vm, v -> vm.rendererPresentMode = v }),
            game = key("presentMode"),
            visible = { env, values -> vulkanRenderer(env, values) },
            disabled = { _, values -> if (values("graphics.nativeRenderer") == "1") "Ignored while the native renderer is on." else null },
        ),
        SettingDef(
            id = "graphics.swapColours", category = "graphics", group = "How the picture reaches the screen", level = EXPERT,
            label = "Colour byte order", tech = "Colors",
            control = { _, _ -> seg("0" to "BGRA (normal)", "1" to "RGBA (swap red/blue)") },
            vm = vmBool({ it.rendererSwapRB }, { vm, v -> vm.rendererSwapRB = v }),
            game = keyTF("swapRB"),
            visible = { env, values -> vulkanRenderer(env, values) },
        ),
        SettingDef(
            id = "graphics.rendererDriver", category = "graphics", group = "How the picture reaches the screen", level = EXPERT,
            label = "Driver for the screen renderer", hint = "Not the game's driver, only the one drawing the desktop.", tech = "Renderer Driver",
            control = { env, _ -> Control.Select(listOf(Opt("system", "System driver")) + env.rendererDrivers.map { Opt(it, it) }) },
            vm = vmString({ it.rendererDriverId }, { vm, v -> vm.rendererDriverId = v }),
            game = key("rendererDriverId"),
            visible = { env, values -> vulkanRenderer(env, values) },
        ),

        // Performance
        SettingDef(
            id = "performance.fpsLimiter", category = "performance", group = "Frame rate", level = SIMPLE,
            label = "Frame rate limiter", hint = "Steadier frame pacing and a cooler device. Set the limit in-game from the side menu.", tech = "FPS Limiter",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.fpsLimiterEnabled }, { vm, v -> vm.fpsLimiterEnabled = v }),
            game = key10("fpsLimiterEnabled"),
        ),
        SettingDef(
            id = "performance.frameGen", category = "performance", group = "Frame generation", level = SIMPLE,
            label = "Frame generation", hint = "Inserts extra frames for smoother motion.", tech = "Frame Generation",
            help = "Built-in (Win-FG) interpolates inside the app. Lossless Scaling uses your own Lossless.dll. Both need the Vulkan screen renderer, Wayland or a Linux entry. The multiplier is set in-game.",
            control = { env, _ ->
                Control.Segmented(listOf(
                    Opt("off", "Off"),
                    Opt("bionic", "Built-in (Win-FG)"),
                    Opt("lsfg-native", "Lossless Scaling",
                        disabledReason = if (env.lsfgDllAvailable) null else "Import Lossless.dll in Settings › Performance first."),
                ))
            },
            vm = vmString({ if (it.frameGenEngine == "lsfg") "lsfg-native" else it.frameGenEngine }, { vm, v -> vm.frameGenEngine = v }),
            game = key("frameGenEngine") { if (it == "lsfg") "lsfg-native" else it },
            disabled = { env, values ->
                if (env.wayland(values) || values("graphics.renderer") == "vulkan") null
                else "Needs the Vulkan screen renderer (Graphics) or Wayland."
            },
        ),
        SettingDef(
            id = "performance.frameGenModel", category = "performance", group = "Frame generation", level = ADVANCED,
            label = "Motion model", tech = "Interpolation model",
            control = { _, _ -> seg("3" to "Optical flow", "4" to "Bidirectional") },
            vm = vmInt({ if (it.frameGenModel == 4) 4 else 3 }, { vm, v -> vm.frameGenModel = v }),
            game = key("frameGenModel") { if (it.trim() == "4") "4" else "3" },
            visible = { _, values -> values("performance.frameGen") == "bionic" },
        ),
        SettingDef(
            id = "performance.lsfgPerformance", category = "performance", group = "Frame generation", level = ADVANCED,
            label = "Performance mode", hint = "Lower interpolation quality for a higher frame rate.", tech = "LSFG performance mode",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.lsfgPerformanceMode }, { vm, v -> vm.lsfgPerformanceMode = v }),
            visible = { _, values -> values("performance.frameGen") == "lsfg-native" },
        ),
        SettingDef(
            id = "performance.translator", category = "performance", group = "x86 translator", level = ADVANCED,
            label = "x86 translator", hint = "Runs the game's x86 code on your ARM CPU.", tech = "Emulator",
            control = { env, _ ->
                val labels = mapOf("fexcore" to "FEX", "box64" to "WOWBox64")
                Control.Segmented((env.vm?.emulatorEntries ?: emptyList()).map { Opt(idOf(it), labels[idOf(it)] ?: it) })
            },
            vm = VmBinding(get = { _, vm -> idOf(vm.selectedEmulator) }, set = { _, vm, v -> vm.selectedEmulator = displayFor(v, vm.emulatorEntries) }),
            game = key("emulator") { it.lowercase(Locale.ENGLISH) },
            visible = { env, _ -> env.arm64ec && !env.isLinuxEntry },
        ),
        SettingDef(
            id = "performance.fexVersion", category = "performance", group = "x86 translator", level = ADVANCED,
            label = "FEX version", tech = "FEXCore Version",
            control = { env, _ -> Control.Select((env.vm?.fexCoreVersionEntries ?: emptyList()).map { Opt(it, it) }) },
            vm = vmString({ it.selectedFEXCoreVersion }, { vm, v -> vm.selectedFEXCoreVersion = v }),
            game = key("fexcoreVersion"),
            visible = { env, values -> env.arm64ec && !env.isLinuxEntry && values("performance.translator") == "fexcore" },
        ),
        SettingDef(
            id = "performance.fexPreset", category = "performance", group = "x86 translator", level = ADVANCED,
            label = "FEX preset", hint = "Trade speed for compatibility. Presets are edited in the classic editor.", tech = "FEXCore Preset",
            control = { env, _ -> Control.Select(env.fexPresets.map { Opt(it.id, it.name) }) },
            vm = VmBinding(
                get = { env, vm -> env.fexPresets.getOrNull(vm.selectedFEXCorePresetIndex)?.id ?: "" },
                set = { _, vm, v -> vm.selectFEXCorePresetById(v) },
            ),
            game = key("fexcorePreset"),
            visible = { env, values -> env.isLinuxEntry || (env.arm64ec && values("performance.translator") == "fexcore") },
        ),
        SettingDef(
            id = "performance.box64Version", category = "performance", group = "x86 translator", level = ADVANCED,
            label = "Box64 version", hint = "Called WOWBox64 on ARM64EC containers.", tech = "Box64 Version / WOWBox64 Version",
            control = { env, _ -> Control.Select((env.vm?.box64VersionEntries ?: emptyList()).map { Opt(it, it) }) },
            vm = vmString({ it.selectedBox64Version }, { vm, v -> vm.selectedBox64Version = v }),
            game = key("box64Version"),
            visible = { env, values -> !env.isLinuxEntry && (!env.arm64ec || values("performance.translator") == "box64") },
        ),
        SettingDef(
            id = "performance.box64Preset", category = "performance", group = "x86 translator", level = ADVANCED,
            label = "Box64 preset", tech = "Box64 Preset / WOWBox64 Preset",
            control = { env, _ -> Control.Select(env.box64Presets.map { Opt(it.id, it.name) }) },
            vm = VmBinding(
                get = { env, vm -> env.box64Presets.getOrNull(vm.selectedBox64PresetIndex)?.id ?: "" },
                set = { _, vm, v -> vm.selectBox64PresetById(v) },
            ),
            game = key("box64Preset"),
            visible = { env, values -> !env.isLinuxEntry && (!env.arm64ec || values("performance.translator") == "box64") },
        ),
        SettingDef(
            id = "performance.startupServices", category = "performance", group = "Windows background services", level = ADVANCED,
            label = "Services started with Windows", hint = "Fewer services = faster start and more free RAM.", tech = "Startup Selection",
            control = { _, _ -> seg("0" to "All", "1" to "Essential only", "2" to "Stop at startup", "3" to "Custom") },
            vm = vmInt({ it.selectedStartupSelection }, { vm, v -> vm.selectedStartupSelection = v }),
            game = intKey("startupSelection"),
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        SettingDef(
            id = "performance.cpuAffinity", category = "performance", group = "CPU cores", level = ADVANCED,
            label = "Cores the game may use", hint = "At least one core stays on.", tech = "Processor Affinity (cpuList)",
            control = { _, _ -> Control.Cores },
            vm = vmString({ it.cpuList }, { vm, v -> vm.cpuList = v }),
            game = key("cpuList"),
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        perfToggle("performance.power.sustained", "sustainedPerfMode", "Sustained performance mode", "Keeps clocks steady for long sessions instead of short bursts."),
        perfToggle("performance.power.priorityBoost", "perfPriorityBoost", "Boost game thread priority", null),
        perfToggle("performance.power.bigCores", "preferBigCores", "Prefer big CPU cores", null),

        // Visual effects (per game only: the container has no vkBasalt setting)
        SettingDef(
            id = "visuals.vkbasalt", category = "visuals", group = "Sharpening", level = ADVANCED,
            label = "Vulkan post-effect (vkBasalt)", hint = "Sharpens the image after it is drawn.", tech = "Sharpness (VKBasalt) › Effect",
            control = { _, _ -> seg("None" to "None", "CAS" to "CAS sharpen", "DLS" to "DLS sharpen + denoise") },
            game = GameBinding(listOf("sharpnessEffect"), read = { e -> e.get("sharpnessEffect")?.takeIf { it.isNotEmpty() } },
                write = { e, v -> e.put("sharpnessEffect", v) }, inherit = Inherit.BUILT_IN, inherited = { "None" }),
            visible = { env, _ -> env.scope == EditorScope.GAME && !env.isLinuxEntry },
        ),
        percentSlider("visuals.vkbasaltLevel", "sharpnessLevel", "vkBasalt strength", "Level") { values -> values("visuals.vkbasalt") != "None" },
        percentSlider("visuals.vkbasaltDenoise", "sharpnessDenoise", "vkBasalt denoise", "Denoise") { values -> values("visuals.vkbasalt") == "DLS" },

        // Audio
        SettingDef(
            id = "audio.driver", category = "audio", group = "Sound", level = SIMPLE,
            label = "Sound system", hint = "PulseAudio suits most games. DirectAudio has the lowest delay on the builds that ship it.", tech = "Audio Driver",
            control = { env, _ ->
                val labels = mapOf("alsa" to "ALSA", "pulseaudio" to "PulseAudio", "directaudio" to "DirectAudio")
                val supported = env.scope == EditorScope.DEFAULTS ||
                    env.vm?.let { DirectAudioSupport.isSupported(it.selectedWineVersion) } == true
                Control.Segmented((env.vm?.audioDriverEntries ?: emptyList()).map { entry ->
                    val id = idOf(entry)
                    Opt(id, labels[id] ?: entry,
                        disabledReason = if (id == "directaudio" && !supported) "Needs a supported ARM64EC Proton build." else null)
                })
            },
            vm = VmBinding(get = { _, vm -> idOf(vm.selectedAudioDriver) }, set = { _, vm, v -> vm.selectedAudioDriver = displayFor(v, vm.audioDriverEntries) }),
            game = key("audioDriver"),
        ),
        SettingDef(
            id = "audio.midiSoundfont", category = "audio", group = "Sound", level = ADVANCED,
            label = "MIDI music sound bank", hint = "Old games that play MIDI music need a sound bank.", tech = "MIDI SoundFont",
            control = { env, _ ->
                Control.Select((env.vm?.midiEntries ?: emptyList()).mapIndexed { i, name ->
                    Opt("$i", when {
                        i == 0 -> "Off"
                        name == MidiManager.DEFAULT_SF2_FILE -> "Built-in General MIDI bank"
                        else -> name
                    })
                })
            },
            vm = vmInt({ it.selectedMidiIndex }, { vm, v -> vm.selectedMidiIndex = v }),
            visible = { env, _ -> !env.isLinuxEntry },
        ),

        // Controls
        SettingDef(
            id = "input.controlsProfile", category = "input", group = "Controllers", level = SIMPLE,
            label = "On-screen controls", hint = "Which touch layout appears over the game.", tech = "Controls Profile",
            control = { env, _ -> Control.Select(listOf(Opt("0", "None")) + env.controlsProfiles.map { Opt("${it.id}", it.name) }) },
            game = GameBinding(
                listOf("controlsProfile"),
                read = { e -> e.get("controlsProfile")?.trim()?.takeIf { it.isNotEmpty() && it != "0" } },
                write = { e, v -> e.put("controlsProfile", if (v == "0") null else v) },
                inherit = Inherit.BUILT_IN, inherited = { "0" },
            ),
            visible = { env, _ -> env.scope == EditorScope.GAME },
        ),
        SettingDef(
            id = "input.exclusiveInput", category = "input", group = "Controllers", level = ADVANCED,
            label = "Only the game receives the controller", hint = "Lets you choose the controller type below. Off gives games both types.", tech = "Exclusive Input",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.exclusiveXInput }, { vm, v -> vm.onExclusiveXInputChanged(v) }),
            // Turning it off gives games both controller types, the same as the classic editors do.
            game = GameBinding(
                listOf("exclusiveXInput"),
                read = { e -> e.get("exclusiveXInput")?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
                write = { e, v -> e.put("exclusiveXInput", v); if (v == "0") e.put("inputType", "$INPUT_BOTH") },
            ),
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        SettingDef(
            id = "input.controllerApi", category = "input", group = "Controllers", level = ADVANCED,
            label = "Controller type games see", hint = "Most modern games want Xbox. Pick Generic for older games, Both if unsure.", tech = "Enable XInput / Enable DInput",
            control = { _, _ -> seg("$INPUT_XINPUT" to "Xbox (XInput)", "$INPUT_DINPUT" to "Generic (DirectInput)", "$INPUT_BOTH" to "Both") },
            vm = VmBinding(
                get = { _, vm -> ((if (vm.enableXInput) INPUT_XINPUT else 0) or (if (vm.enableDInput) INPUT_DINPUT else 0)).toString() },
                set = { _, vm, v ->
                    v.toIntOrNull()?.let { t ->
                        vm.enableXInput = t and INPUT_XINPUT != 0
                        vm.enableDInput = t and INPUT_DINPUT != 0
                    }
                },
            ),
            game = intKey("inputType"),
            visible = { env, values -> !env.isLinuxEntry && values("input.exclusiveInput") == "1" },
        ),
        SettingDef(
            id = "input.touchMode", category = "input", group = "Controllers", level = ADVANCED,
            label = "Touch acts as", hint = "Touchscreen sends real touches to the game.", tech = "Touchscreen Mode",
            control = { _, _ -> seg("0" to "Mouse / touchpad", "1" to "Touchscreen") },
            game = key10("simTouchScreen").let { GameBinding(it.keys, it.read, it.write, Inherit.BUILT_IN, inherited = { "0" }) },
            visible = { env, _ -> env.scope == EditorScope.GAME },
        ),
        SettingDef(
            id = "input.hideOnController", category = "input", group = "Player slots", level = SIMPLE,
            label = "Hide on-screen controls when a controller connects",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.autoHideControlsOnPad }, { vm, v -> vm.autoHideControlsOnPad = v }),
            game = key10("autoHideControlsOnPad"),
        ),
        SettingDef(
            id = "input.onScreenPriority", category = "input", group = "Player slots", level = ADVANCED,
            label = "On-screen controls vs. a real pad", tech = "On-screen priority",
            control = { _, _ ->
                seg("${Container.ON_SCREEN_MODE_KEEP}" to "On-screen stays Player 1",
                    "${Container.ON_SCREEN_MODE_YIELD}" to "Pad becomes Player 1",
                    "${Container.ON_SCREEN_MODE_SHARE}" to "Both control Player 1")
            },
            vm = vmInt({ it.onScreenControllerMode }, { vm, v -> vm.onScreenControllerMode = v }),
            game = intKey("onScreenControllerMode"),
        ),
        SettingDef(
            id = "input.relativeMouse", category = "input", group = "Mouse & touch", level = ADVANCED,
            label = "Relative mouse (for 3D camera games)", hint = "Also switchable in-game from the side menu.",
            control = { _, _ -> Control.Toggle },
            game = key10("relativeMouse").let {
                GameBinding(it.keys, it.read, it.write, Inherit.CONTAINER,
                    inherited = { env -> if (env.vm?.container?.getExtra("relativeMouse", "0") == "1") "1" else "0" }, builtIn = "0")
            },
            visible = { env, _ -> env.scope == EditorScope.GAME },
        ),
        SettingDef(
            id = "input.mouseWarp", category = "input", group = "Mouse & touch", level = EXPERT,
            label = "Mouse capture (DirectInput)", hint = "Fixes spinning or stuck cameras in some older games.", tech = "Mouse Warp Override",
            control = { _, _ -> seg("0" to "Off", "1" to "On", "2" to "Forced") },
            vm = vmInt({ it.selectedMouseWarpIndex }, { vm, v -> vm.selectedMouseWarpIndex = v }),
            onDefaults = false, showInGame = false,
            visible = { env, _ -> env.scope == EditorScope.CONTAINER && !env.isLinuxEntry },
        ),
        SettingDef(
            id = "input.vibrationTarget", category = "input", group = "Rumble", level = SIMPLE,
            label = "Rumble on", tech = "Vibration mode",
            control = { _, _ ->
                seg("${Container.VIBRATION_MODE_OFF}" to "Off", "${Container.VIBRATION_MODE_CONTROLLER}" to "Controller",
                    "${Container.VIBRATION_MODE_DEVICE}" to "This device", "${Container.VIBRATION_MODE_BOTH}" to "Both")
            },
            vm = vmInt({ it.vibrationMode }, { vm, v -> vm.vibrationMode = v }),
            game = intKey("vibrationMode"),
        ),
        SettingDef(
            id = "input.vibrationIntensity", category = "input", group = "Rumble", level = ADVANCED,
            label = "Rumble strength", tech = "Vibration intensity",
            control = { _, _ -> Control.Slider(0f, 100f, 5f, { "${it.toInt()}%" }, { "${it.toInt()}" }) },
            vm = vmInt({ it.vibrationIntensity }, { vm, v -> vm.vibrationIntensity = v }),
            game = intKey("vibrationIntensity"),
            visible = { _, values -> values("input.vibrationTarget") != "${Container.VIBRATION_MODE_OFF}" },
        ),
        SettingDef(
            id = "input.gyro.enabled", category = "input", group = "Motion aim (gyro)", level = SIMPLE,
            label = "Motion aim", hint = "Tilt the device to aim.", tech = "Enable Motion Aim",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.gyroEnabled }, { vm, v -> vm.gyroEnabled = v }),
            game = key10("gyroEnabled"),
        ),
        SettingDef(
            id = "input.gyro.mode", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Motion style", tech = "Motion Mode",
            control = { _, _ -> seg("${Container.GYRO_MODE_RATE}" to "Turn speed", "${Container.GYRO_MODE_ORIENTATION}" to "Tilt to aim") },
            // Tilt to aim can't drive the mouse, so it moves a mouse target to the right stick, as the classic editors do.
            vm = VmBinding(
                get = { _, vm -> vm.gyroMode.toString() },
                set = { _, vm, v ->
                    vm.gyroMode = v.toIntOrNull() ?: Container.GYRO_MODE_RATE
                    if (vm.gyroMode == Container.GYRO_MODE_ORIENTATION && vm.gyroTarget == Container.GYRO_TARGET_MOUSE) vm.gyroTarget = Container.GYRO_TARGET_RIGHT_STICK
                },
            ),
            game = GameBinding(
                listOf("gyroMode"),
                read = { e -> e.get("gyroMode")?.trim()?.toIntOrNull()?.toString() },
                write = { e, v ->
                    e.put("gyroMode", v)
                    if (v == "${Container.GYRO_MODE_ORIENTATION}" && e.get("gyroTarget") == "${Container.GYRO_TARGET_MOUSE}") e.put("gyroTarget", "${Container.GYRO_TARGET_RIGHT_STICK}")
                },
            ),
            visible = { _, values -> values("input.gyro.enabled") == "1" },
        ),
        SettingDef(
            id = "input.gyro.target", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Motion moves", tech = "Motion Target",
            control = { _, values ->
                Control.Segmented(listOf(
                    Opt("${Container.GYRO_TARGET_RIGHT_STICK}", "Right stick"),
                    Opt("${Container.GYRO_TARGET_LEFT_STICK}", "Left stick"),
                    Opt("${Container.GYRO_TARGET_MOUSE}", "Mouse",
                        disabledReason = if (values("input.gyro.mode") == "${Container.GYRO_MODE_ORIENTATION}") "Not with Tilt to aim." else null),
                ))
            },
            vm = vmInt({ it.gyroTarget }, { vm, v -> vm.gyroTarget = v }),
            game = intKey("gyroTarget"),
            visible = { _, values -> values("input.gyro.enabled") == "1" },
        ),
        SettingDef(
            id = "input.gyro.activator", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Hold this to aim", tech = "Activator Button",
            control = { _, _ ->
                seg("${Container.GYRO_ACTIVATOR_L1}" to "L1", "${Container.GYRO_ACTIVATOR_L2}" to "L2", "${Container.GYRO_ACTIVATOR_R1}" to "R1",
                    "${Container.GYRO_ACTIVATOR_R3}" to "R3", "${Container.GYRO_ACTIVATOR_ALWAYS}" to "Always on")
            },
            vm = vmInt({ it.gyroActivator }, { vm, v -> vm.gyroActivator = v }),
            game = intKey("gyroActivator"),
            visible = { _, values -> values("input.gyro.enabled") == "1" },
        ),
        SettingDef(
            id = "input.gyro.activation", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Button behaviour", tech = "Activation",
            control = { _, _ -> seg("${Container.GYRO_ACTIVATION_HOLD}" to "Hold", "${Container.GYRO_ACTIVATION_TOGGLE}" to "Toggle") },
            vm = vmInt({ it.gyroActivationMode }, { vm, v -> vm.gyroActivationMode = v }),
            game = intKey("gyroActivationMode"),
            visible = { _, values -> values("input.gyro.enabled") == "1" && values("input.gyro.activator") != "${Container.GYRO_ACTIVATOR_ALWAYS}" },
        ),
        SettingDef(
            id = "input.gyro.sensitivity", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Sensitivity", tech = "Sensitivity",
            control = { _, _ -> Control.Slider(0.1f, 10f, 0.1f, { fmt1(it) }, { fmt1(it) }) },
            vm = VmBinding(get = { _, vm -> fmt1(vm.gyroSensitivity) }, set = { _, vm, v -> v.toFloatOrNull()?.let { vm.gyroSensitivity = it } }),
            game = key("gyroSensitivity") { v -> v.toFloatOrNull()?.let { fmt1(it) } },
            visible = { _, values -> values("input.gyro.enabled") == "1" },
        ),
        SettingDef(
            id = "input.gyro.deadzone", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Ignore small movements", hint = "Set per container: it describes your hands, not the game.", tech = "Deadzone",
            control = { _, _ -> Control.Slider(0f, 0.5f, 0.01f, { fmt2(it) }, { fmt2(it) }) },
            vm = VmBinding(get = { _, vm -> fmt2(vm.gyroDeadzone) }, set = { _, vm, v -> v.toFloatOrNull()?.let { vm.gyroDeadzone = it } }),
            visible = { _, values -> values("input.gyro.enabled") == "1" },
        ),
        SettingDef(
            id = "input.gyro.smoothing", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Smoothing", hint = "Less jitter, a little more delay. Set per container.", tech = "Smoothing",
            control = { _, _ -> Control.Slider(0f, 0.95f, 0.05f, { fmt2(it) }, { fmt2(it) }) },
            vm = VmBinding(get = { _, vm -> fmt2(vm.gyroSmoothing) }, set = { _, vm, v -> v.toFloatOrNull()?.let { vm.gyroSmoothing = it } }),
            visible = { _, values -> values("input.gyro.enabled") == "1" },
        ),
        SettingDef(
            id = "input.gyro.invertX", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Invert left/right", tech = "Invert X",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.gyroInvertX }, { vm, v -> vm.gyroInvertX = v }),
            game = key10("gyroInvertX"),
            visible = { _, values -> values("input.gyro.enabled") == "1" },
        ),
        SettingDef(
            id = "input.gyro.invertY", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Invert up/down", tech = "Invert Y",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.gyroInvertY }, { vm, v -> vm.gyroInvertY = v }),
            game = key10("gyroInvertY"),
            visible = { _, values -> values("input.gyro.enabled") == "1" },
        ),

        // Performance overlay: the launch path reads the on/off switch from the container only.
        SettingDef(
            id = "hud.show", category = "hud", group = "Overlay", level = SIMPLE,
            label = "Show performance overlay", hint = "FPS, CPU/GPU load and temperatures on top of the game. Change its style in-game.", tech = "Show FPS",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.showFPS }, { vm, v -> vm.showFPS = v }),
        ),

        // Windows
        SettingDef(
            id = "windows.containerName", category = "windows", group = "Container", level = SIMPLE,
            label = "Container name", hint = "Shown on the Containers screen and in each game's \"From container\" labels.", tech = "Name",
            control = { _, _ -> Control.TextField("Container name") { if (it.isBlank()) "The name can't be empty." else null } },
            vm = vmString({ it.containerName }, { vm, v -> vm.containerName = v }),
            onDefaults = false, showInGame = false,
            visible = { env, _ -> env.scope == EditorScope.CONTAINER },
        ),
        SettingDef(
            id = "windows.locale", category = "windows", group = "Language & region", level = ADVANCED,
            label = "Language & region", hint = "Fixes garbled text in Japanese, Chinese, Korean and Russian games.", tech = "LC_ALL",
            control = { env, _ ->
                Control.Select(
                    options = (env.vm?.lcAllEntries ?: emptyList()).map { lc ->
                        val loc = Locale.forLanguageTag(lc.replace('_', '-'))
                        Opt("$lc.UTF-8", "${loc.getDisplayLanguage(Locale.ENGLISH)} (${loc.getDisplayCountry(Locale.ENGLISH)})")
                    },
                    customLabel = "Other locale…",
                    customHint = "e.g. pl_PL.UTF-8",
                    validateCustom = { if (LOCALE.matches(it.trim())) null else "Type a locale like pl_PL or pl_PL.UTF-8." },
                    normalizeCustom = { t -> t.trim().let { if ('.' in it) it else "$it.UTF-8" } },
                )
            },
            vm = vmString({ it.lcAll }, { vm, v -> vm.lcAll = v }),
            game = key("lc_all"),
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        SettingDef(
            id = "windows.closeOnExit", category = "windows", group = "System", level = ADVANCED,
            label = "Close the session when the game exits", tech = "Close when game exits",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.autoCloseOnExit }, { vm, v -> vm.autoCloseOnExit = v }),
            game = key10("autoCloseOnExit"),
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        SettingDef(
            id = "windows.runAsAdmin", category = "windows", group = "System", level = ADVANCED,
            label = "Run programs as administrator", hint = "Avoids permission prompts inside Windows (turns UAC off).", tech = "Run as administrator (EnableLUA)",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.runAsAdmin }, { vm, v -> vm.runAsAdmin = v }),
            showInGame = false,
            visible = { env, _ -> !env.isLinuxEntry },
        ),
        SettingDef(
            id = "windows.theme", category = "windows", group = "Windows desktop", level = ADVANCED,
            label = "Desktop theme", tech = "Theme",
            control = { _, _ -> seg("0" to "Light", "1" to "Dark") },
            vm = vmInt({ it.desktopThemeIndex }, { vm, v -> vm.desktopThemeIndex = v }),
            showInGame = false,
            visible = { env, _ -> !env.isLinuxEntry },
        ),
    )

    private val RENDERERS = listOf("OpenGL", "Vulkan", "SurfaceFlinger")
    private val INPUT_XINPUT = com.winlator.star.winhandler.WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
    private val INPUT_DINPUT = com.winlator.star.winhandler.WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
    private val INPUT_BOTH = INPUT_XINPUT or INPUT_DINPUT

    /** A power toggle: per game it overrides the app-wide default (Settings › Performance); there is no container level. */
    private fun perfToggle(id: String, key: String, label: String, hint: String?) = SettingDef(
        id = id, category = "performance", group = "Power", level = ADVANCED,
        label = label, hint = hint,
        control = { _, _ -> Control.Toggle },
        game = GameBinding(
            listOf(key),
            read = { e -> e.get(key)?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
            write = { e, v -> e.put(key, v) },
            inherit = Inherit.APP,
            inherited = { onOff(runCatching { PerformanceSettings.globalDefault(key) }.getOrDefault(false)) },
        ),
        visible = { env, _ -> env.scope == EditorScope.GAME },
    )

    /** A 0–100 % vkBasalt slider stored on the game. The launch path parses it as a number; 100 when unset. */
    private fun percentSlider(id: String, key: String, label: String, tech: String, show: ((String) -> String) -> Boolean) = SettingDef(
        id = id, category = "visuals", group = "Sharpening", level = ADVANCED,
        label = label, tech = tech,
        control = { _, _ -> Control.Slider(0f, 100f, 5f, { "${it.toInt()}%" }, { "${it.toInt()}" }) },
        game = GameBinding(
            listOf(key),
            read = { e -> e.get(key)?.toFloatOrNull()?.toInt()?.toString() },
            write = { e, v -> e.put(key, v) },
            inherit = Inherit.BUILT_IN, inherited = { "100" },
        ),
        visible = { env, values -> env.scope == EditorScope.GAME && !env.isLinuxEntry && show(values) },
    )
}
