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
import androidx.compose.material.icons.filled.Widgets
import com.winlator.star.FeatureFlags
import com.winlator.star.container.Container
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog
import com.winlator.star.core.BannerinXInput
import com.winlator.star.core.ControllerIdentity
import com.winlator.star.core.DInputVisibility
import com.winlator.star.core.DirectAudioSupport
import com.winlator.star.core.FastOpenGL
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.LinuxVulkanDriver
import com.winlator.star.core.MediaDecoder
import com.winlator.star.core.RumbleMotors
import com.winlator.star.core.StickPacing
import com.winlator.star.core.StringUtils
import com.winlator.star.core.SyncMode
import com.winlator.star.core.SyncSupport
import com.winlator.star.core.UnrealHdr
import com.winlator.star.display.WaylandHdr
import com.winlator.star.linux.LinuxTuning
import com.winlator.star.midi.MidiManager
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import com.winlator.star.ui.deck.settings.SettingLevel.EXPERT
import com.winlator.star.ui.deck.settings.SettingLevel.SIMPLE
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.MALI_SYSTEM_DRIVER_NOTE
import com.winlator.star.ui.screens.compositorDriverLabel
import com.winlator.star.ui.screens.compositorDriverMaliSystem
import com.winlator.star.ui.screens.compositorDriverUnusable
import com.winlator.star.ui.screens.withGraphicsDriverVersion
import java.util.Locale

// Every setting the Deck editor edits itself, for a game, a container and All containers. Labels,
// hints and grouping follow the Deck design (proto/data/settings.js). The storage is today's: the
// container form writes exactly what the classic container editor writes, and a game writes the same
// shortcut extras the classic game editor writes, but only the ones the user actually changed.
//
// Per-game rows are only offered where the launch path reads the shortcut extra with the container as
// the fallback (XServerDisplayActivity, GuestProgramLauncherComponent). A container field the launch
// never reads per game (MIDI, the overlay switch, gyro deadzone and smoothing) is shown read-only in a
// game's editor as a container setting.
//
// Each row names its worlds (World). The Linux Steam client entry gets exactly the rows the classic game
// editor shows it (its isLinuxEntry gates), an Android game only its name, and a Wine game never a
// Linux runtime row.

internal object SettingsRegistry {

    val categories: List<SettingsCategory> = listOf(
        SettingsCategory("game", "Game", "Name and launch options for this one game.", Icons.Filled.SportsEsports),
        SettingsCategory("launch", "Launch", "How this game starts: Steam or not, controller hand-off, anti-cheat, Epic sign-in.", Icons.Filled.RocketLaunch),
        SettingsCategory("display", "Display", "Resolution, how the picture fills the screen, refresh rate and HDR.", Icons.Filled.Monitor),
        SettingsCategory("graphics", "Graphics", "DirectX translation, GPU driver, OpenGL and how the picture is put on screen.", Icons.Filled.Layers),
        SettingsCategory("performance", "Performance", "Frame rate limit, frame generation, sync, x86 translators, CPU cores, background services.", Icons.Filled.Speed),
        SettingsCategory("visuals", "Visual effects", "Sharpening and other post effects.", Icons.Filled.AutoAwesome),
        SettingsCategory("audio", "Audio", "Sound driver and MIDI music.", Icons.AutoMirrored.Filled.VolumeUp),
        SettingsCategory("input", "Controls", "Controllers, on-screen controls, mouse and touch, rumble and motion aim.", Icons.Filled.Gamepad),
        SettingsCategory("hud", "Performance overlay", "The on-screen FPS and temperature readout.", Icons.Filled.MonitorHeart),
        SettingsCategory("windows", "Windows", "Container name, language, desktop and session behaviour.", Icons.Filled.WebAsset),
        SettingsCategory(CAT_COMPONENTS, "Windows components", "Install runtimes and libraries into the container, and pick Wine's or Microsoft's DLLs.", Icons.Filled.Widgets),
        SettingsCategory("linux", "Linux runtime", "Only for the Linux Steam client entry: its drivers, Steam client options and speed tweaks.", Icons.Filled.Terminal),
        SettingsCategory("expert", "Expert", "Raw environment variables and translator variables.", Icons.Filled.Build),
    )

    val defs: List<SettingDef> by lazy { buildDefs() }

    /** The classic game editor. */
    const val TARGET_CLASSIC = "classic"
    /** A game's Game Details sheet (name, cover art, Steam link, genres), run by the Games screen. */
    const val TARGET_DETAILS = "details"
    /** Change the program a game runs, over the current Deck page (the shell's game-actions host). */
    const val TARGET_CHANGE_EXE = "change_exe"
    /** Copy a game to drive C, over the current Deck page. */
    const val TARGET_DRIVE_C = "drive_c"
    /** Information only, nothing to open. */
    const val TARGET_NONE = "none"
    /** Another category of this editor (see [categoryTarget]). */
    const val TARGET_CATEGORY = "category:"

    /** The Windows components page: install lists (DeckComponentsPage) above and below its DLL rows. */
    const val CAT_COMPONENTS = "components"

    fun categoryTarget(id: String) = TARGET_CATEGORY + id

    /**
     * Rows that open another screen (or say where something lives) for what is not edited here. Each
     * names its worlds. None opens the classic editor any more ([TARGET_CLASSIC] stays handled by the
     * screens for a hand-off that may need it again).
     */
    @Suppress("UNUSED_PARAMETER")
    fun handoffs(scope: EditorScope, env: SettingsEnv): Map<String, List<Handoff>> {
        val game = scope == EditorScope.GAME
        val m = linkedMapOf<String, List<Handoff>>()
        if (game) {
            m["game"] = listOf(
                Handoff("Name, cover art and details", "Cover art from your files, Steam or SteamGridDB, the Steam link, genres, year and description (Game Details).", TARGET_DETAILS, ALL_WORLDS),
                // The icon is picked on this page now (DeckIconSection); the file operations stay with the Games screen.
                Handoff("Change the program to run", "Pick another .exe in the game's folder for this entry.", TARGET_CHANGE_EXE, WINE_ONLY),
                Handoff("Copy the game to drive C", "Copy the game's folder into the container's drive C and run it from there.", TARGET_DRIVE_C, WINE_ONLY),
            )
            m["launch"] = listOf(Handoff("Offline Steam mode, file checks and updates", "Goldberg mode, Verify files and Check for updates live in the launch menu. Turn off \"Skip the launch menu\" to see it on the next start.", TARGET_NONE))
        }
        // Display, Graphics, Performance, Visual effects, Audio, Controls and the overlay have no hand-offs any
        // more: the TV rows, the Wayland driver gear, an imported wrapper's detected settings, Custom services,
        // preset values, power and root, ReShade, sound latency and the microphone, player slots, the XR
        // mapping and the overlay style are rows and sections of their pages (the Deck*Settings files).
        m["windows"] = listOfNotNull(
            // Its own page now: the install lists and the builtin / native DLL rows (CAT_COMPONENTS).
            Handoff(
                "Windows components",
                if (scope == EditorScope.DEFAULTS) "Builtin or native DLLs for DirectX, audio and runtimes, and the media codecs."
                else "Install runtimes and libraries into the container, and pick builtin or native DLLs for DirectX, audio and runtimes.",
                categoryTarget(CAT_COMPONENTS),
            ),
            // The Wine layer, wallpaper and colours, the drives and Reset to app defaults are this page's own (DeckWindowsSettings).
        )
        // The Linux entry's cores, Games folders and component swaps are the Linux runtime page's own
        // (LinuxRuntimeRows, DeckLinuxSection), and the environment variables the Expert page's
        // (DeckEnvVarsSection), for every scope and world that had the classic Env Vars tab.
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

    /** A per-game on/off extra with no container level: absent means [default]. */
    private fun builtIn10(k: String, default: Boolean) = GameBinding(
        keys = listOf(k),
        read = { e -> e.get(k)?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
        write = { e, v -> e.put(k, v) },
        inherit = Inherit.BUILT_IN, inherited = { onOff(default) },
    )

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
    private fun gameScope(env: SettingsEnv) = env.scope == EditorScope.GAME

    // ── Driver config ("k=v;k=v"): only its "version" key is edited here ────────────────────────

    private const val GDC = "graphicsDriverConfig"
    private const val ID_DRIVER_VERSION = "graphics.compositorDriver"

    private fun gdcVersion(config: String?): String =
        config?.takeIf { it.isNotEmpty() }?.let { runCatching { GraphicsDriverConfigDialog.getVersion(it) }.getOrNull() } ?: ""

    /** The game's driver config with [version] in place of its own, or null when that equals the container's. */
    private fun gdcWith(e: GameExtras, version: String): String? {
        val containerCfg = e.container.getGraphicsDriverConfig() ?: ""
        val next = withGraphicsDriverVersion(e.get(GDC)?.takeIf { it.isNotEmpty() } ?: containerCfg, version)
        return next.takeIf { it != containerCfg }
    }

    /** The Wayland compositor driver (the "version" key of the driver config), for a container and a game. */
    private val compositorVm = VmBinding(
        get = { _, vm -> gdcVersion(vm.graphicsDriverConfig) },
        set = { _, vm, v -> vm.onCompositorDriverPicked(v) },
    )
    private val compositorGame = GameBinding(
        keys = listOf(GDC),
        read = { e ->
            e.get(GDC)?.takeIf { it.isNotEmpty() }?.let { gdcVersion(it) }
                ?.takeIf { it != gdcVersion(e.container.getGraphicsDriverConfig()) }
        },
        write = { e, v -> e.put(GDC, gdcWith(e, v)) },
        reset = { e -> if (e.get(GDC) != null) e.put(GDC, gdcWith(e, gdcVersion(e.container.getGraphicsDriverConfig()))) },
    )

    private fun compositorControl(env: SettingsEnv, current: String): Control {
        val loaded = env.compositorChoices
        val choices = loaded ?: emptyList()
        val shown = choices.map { Opt(it, it) }
        // What is stored is always listed, so the field never reads blank ("" behaves as "System").
        return Control.Select(
            if (current in choices) shown
            else listOf(Opt(current, compositorDriverLabel(current, choices, loaded != null))) + shown,
        )
    }

    private fun compositorNote(env: SettingsEnv, version: String): String? {
        val loaded = env.compositorChoices ?: return "Checking which installed drivers can show the game…"
        // The auto-pick notes belong to the container form being edited, not to the container a game follows.
        val vm = if (env.scope != EditorScope.GAME) env.vm else null
        return when {
            vm != null && vm.compositorDriverSearching -> "Looking for an installed Turnip that can import the game's frames…"
            compositorDriverMaliSystem(version, loaded) -> MALI_SYSTEM_DRIVER_NOTE
            compositorDriverUnusable(version, loaded, true) ->
                if (env.isLinuxEntry) "This driver cannot show the session. \"System\" or a driver that is no longer installed leaves the compositor on the system Vulkan, which shows a black screen."
                else "Wayland needs a Turnip driver here. \"System\" or a missing driver cannot import the game's frames and shows a black screen. Download one below (Download a Turnip driver)."
            vm != null && vm.compositorDriverAutoPicked == version ->
                if (vm.compositorDriverPickedFromDefaults) "Picked for you: the driver in your New Container Defaults."
                else "Picked for you: the newest installed Turnip that can import the game's frames."
            else -> null
        }
    }

    // ── Win Components ("key=index,key=index"): the codec rows edit one key each ─────────────────

    private const val WINCOMPONENTS = "wincomponents"

    private fun wcIndex(components: String?, key: String): Int? {
        if (components.isNullOrEmpty()) return null
        for (entry in KeyValueSet(components)) if (entry[0] == key) return entry[1].toIntOrNull()
        return null
    }

    private fun wcWith(components: String, key: String, index: Int): String =
        components.split(",").joinToString(",") { if (it.substringBefore('=') == key) "$key=$index" else it }

    /** The game's components string with [key] set, or null when that equals the container's. */
    private fun wcGameWith(e: GameExtras, key: String, index: Int): String? {
        val containerWc = e.container.getWinComponents() ?: ""
        val next = wcWith(e.get(WINCOMPONENTS)?.takeIf { it.isNotEmpty() } ?: containerWc, key, index)
        return next.takeIf { it != containerWc }
    }

    // ── DX wrapper: leaving VEGAS drops its config file, as the classic editors do ─────────────

    private fun stripVegasConfig(config: String): String =
        if (config.split(",").none { it.startsWith("dxvkConfigFile=") && it.length > "dxvkConfigFile=".length }) config
        else config.split(",").filter { !it.startsWith("dxvkConfigFile=") }.joinToString(",")

    // ── Sync ────────────────────────────────────────────────────────────────────────────────────

    private fun syncReason(env: SettingsEnv, mode: String): String? {
        val caps = env.vm?.syncCaps
        val layer = env.vm?.selectedWineVersion.orEmpty()
        return when {
            mode == SyncMode.FSYNC -> "Android blocks fsync"
            caps == null -> if (mode == SyncMode.NTSYNC) "Checking what $layer supports…" else null
            !caps.isAvailable(mode) -> "$mode isn't in $layer"
            else -> null
        }
    }

    /** The sync variables a game's own env string carried before the selector existed go when it is set. */
    private fun stripGameSyncVars(e: GameExtras) {
        val own = e.get("envVars") ?: return
        val stripped = SyncSupport.stripSyncVars(own)
        if (stripped != own) e.put("envVars", stripped.ifEmpty { null })
    }

    // ── Linux runtime ───────────────────────────────────────────────────────────────────────────

    /** A Linux entry switch: one "1" / "0" extra, LinuxTuning's default when absent. */
    private fun linuxToggle(
        id: String, extra: String, group: String, label: String, level: SettingLevel,
        hint: String? = null,
        visible: (SettingsEnv, (String) -> String) -> Boolean = { env, _ -> gameScope(env) },
        confirm: ((String) -> Confirm?)? = null,
        note: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
    ) = SettingDef(
        id = id, category = "linux", group = group, label = label, hint = hint, tech = extra, level = level,
        control = { _, _ -> Control.Toggle },
        game = builtIn10(extra, LinuxTuning.defaultOn(extra)),
        worlds = LINUX_ONLY, visible = visible, confirm = confirm, note = note,
    )

    /** A Linux entry choice stored as one of [allowed] ("" = unset, which is removed). */
    private fun linuxChoice(extra: String, allowed: List<String>) = GameBinding(
        keys = listOf(extra),
        read = { e -> e.get(extra)?.takeIf { it.isNotEmpty() && it in allowed } },
        write = { e, v -> e.put(extra, v.ifEmpty { null }) },
        inherit = Inherit.BUILT_IN, inherited = { "" },
    )

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
            visible = { env, _ -> gameScope(env) },
            worlds = ALL_WORLDS,
        ),
        SettingDef(
            id = "game.arguments", category = "game", group = "Name", level = ADVANCED,
            label = "Launch options", hint = "Extra words added after the program name, e.g. -windowed -dx11.", tech = "Exec Arguments",
            control = { _, _ -> Control.TextField("-windowed") },
            game = GameBinding(listOf("execArgs"), read = { e -> e.get("execArgs") ?: "" }, write = { e, v -> e.put("execArgs", v.ifBlank { null }) }, inherit = Inherit.NONE),
            visible = { env, _ -> gameScope(env) },
        ),

        // Launch (per game only)
        SettingDef(
            id = "launch.method", category = "launch", group = "How it starts", level = SIMPLE,
            label = "Start the game with", tech = "Launch with: SteamLite / Headless Steam / Goldberg / Raw .exe",
            help = "SteamLite runs the real Steam client so achievements, cloud saves and online play work. Headless Steam runs the game on the app's own Steam session, so friends and chat stay online (experimental). Goldberg pretends to be Steam without signing in. Raw .exe starts the game's own program.",
            control = { _, _ -> seg("RealSteam" to "SteamLite", "AppSteam" to "Headless Steam", "Goldberg" to "Goldberg (offline)", "Raw" to "Raw .exe") },
            game = GameBinding(
                listOf("launchMode"),
                read = { e -> e.get("launchMode")?.takeIf { it in LAUNCH_MODES } },
                write = { e, v -> e.put("launchMode", v) },
                inherit = Inherit.BUILT_IN, inherited = { "RealSteam" },
            ),
            visible = { env, _ -> gameScope(env) && env.isSteam },
        ),
        SettingDef(
            id = "launch.rememberChoice", category = "launch", group = "How it starts", level = SIMPLE,
            label = "Skip the launch menu", hint = "Start straight away with the saved choices. Turn off to see the launch menu again.", tech = "Remember my choice",
            control = { _, _ -> Control.Toggle },
            game = builtIn10("launchModeRemembered", false),
            visible = { env, _ -> gameScope(env) },
        ),
        SettingDef(
            id = "launch.controllerPassthrough", category = "launch", group = "Steam options", level = ADVANCED,
            label = "Let the game handle the controller", hint = "Hands the pad straight to the game instead of Steam Input. For classic games (Half-Life 2, CS:S). Turns Steam Input off.", tech = "Controller passthrough",
            control = { _, _ -> Control.Toggle },
            // The opposite of Steam Input, so turning one on turns the other off, as the launch menu does.
            game = GameBinding(
                listOf("controllerPassthrough"),
                read = { e -> e.get("controllerPassthrough")?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
                write = { e, v ->
                    e.put("controllerPassthrough", v)
                    if (v == "1" && e.get("steamInput") == "1") e.put("steamInput", "0")
                },
                inherit = Inherit.BUILT_IN, inherited = { "0" },
            ),
            visible = { env, values -> gameScope(env) && env.isSteam && values("launch.method") == "RealSteam" },
        ),
        SettingDef(
            id = "launch.steamInput", category = "launch", group = "Steam options", level = ADVANCED,
            label = "Steam Input", hint = "For games that only see a pad through Steam (No Man's Sky). Keep off for games that read the pad themselves. Turns passthrough off.", tech = "steamInput",
            help = "Some games never read the controller themselves: they ask Steam for it. In SteamLite nobody answers, so those games see no pad. On: the SteamLite agent switches Steam's controller layer on for this game with a fitting layout. Keep it off for games that already read a pad, or they get every press twice. The opposite of controller passthrough, so only one can be on. SteamLite only.",
            control = { _, _ -> Control.Toggle },
            game = GameBinding(
                listOf("steamInput"),
                read = { e -> e.get("steamInput")?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
                write = { e, v ->
                    e.put("steamInput", v)
                    if (v == "1" && e.get("controllerPassthrough") == "1") e.put("controllerPassthrough", "0")
                },
                inherit = Inherit.BUILT_IN, inherited = { "0" },
            ),
            visible = { env, values -> gameScope(env) && env.isSteam && values("launch.method") == "RealSteam" },
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
            visible = { env, values -> gameScope(env) && env.isSteam && values("launch.method") == "RealSteam" },
        ),
        SettingDef(
            id = "launch.epicEos", category = "launch", group = "Epic Games", level = SIMPLE,
            label = "Epic Online Services (EOS) sign-in", hint = "Signs EOS games in with your Epic account.", tech = "epicEos",
            control = { _, _ -> Control.Toggle },
            game = GameBinding(
                listOf("epicEos"),
                read = { e -> e.get("epicEos")?.takeIf { it.isNotEmpty() }?.let { if (it == "0") "0" else "1" } },
                write = { e, v -> e.put("epicEos", v) },
                inherit = Inherit.BUILT_IN, inherited = { "1" },
            ),
            visible = { env, _ -> gameScope(env) && env.isEpic },
        ),
        SettingDef(
            id = "launch.epicOvtForce", category = "launch", group = "Epic Games", level = ADVANCED,
            label = "Force Denuvo ownership token", hint = "For Denuvo games the app doesn't detect on its own.", tech = "epicOvtForce",
            control = { _, _ -> Control.Toggle },
            game = builtIn10("epicOvtForce", false),
            visible = { env, _ -> gameScope(env) && env.isEpic },
            disabled = { _, values -> if (values("launch.epicEos") == "1") null else "Needs EOS sign-in on." },
        ),
        SettingDef(
            id = "launch.epicOffline", category = "launch", group = "Epic Games", level = ADVANCED,
            label = "Launch offline", hint = "Skips Epic sign-in for this game (no online features).", tech = "epicOffline",
            control = { _, _ -> Control.Toggle },
            game = builtIn10("epicOffline", false),
            visible = { env, _ -> gameScope(env) && env.isEpic },
            disabled = { _, values -> if (values("launch.epicEos") == "1") null else "Needs EOS sign-in on." },
        ),
        SettingDef(
            id = "launch.epicOverlay", category = "launch", group = "Epic Games", level = EXPERT,
            label = "Epic Friends Overlay", hint = "Experimental: Epic's overlay in-game on Shift+F3. Needs the container to allow full services.", tech = "epicOverlay",
            control = { _, _ -> Control.Toggle },
            game = builtIn10("epicOverlay", false),
            visible = { env, _ -> gameScope(env) && env.isEpic && FeatureFlags.EPIC_OVERLAY_ENABLED },
        ),

        // Display
        SettingDef(
            id = "display.resolution", category = "display", group = "Screen", level = SIMPLE,
            label = "Game resolution", hint = "The screen size the game sees. Lower = faster.", tech = "Screen Size",
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
            worlds = WINE_LINUX,
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
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = "display.alignment", category = "display", group = "Screen", level = ADVANCED,
            label = "Picture position", hint = "Useful on foldables: keep the game on one half.", tech = "Screen Alignment",
            control = { _, _ -> seg("${Container.ALIGN_CENTER}" to "Centre", "${Container.ALIGN_TOP}" to "Top", "${Container.ALIGN_BOTTOM}" to "Bottom") },
            vm = vmInt({ it.screenAlignment }, { vm, v -> vm.screenAlignment = v }),
            game = intKey("screenAlignment"),
            worlds = WINE_LINUX,
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
            visible = { env, _ -> env.panelRates.isNotEmpty() },
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
            help = WaylandHdr.HELP_TEXT,
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.waylandHdr }, { vm, v -> vm.waylandHdr = v }),
            game = key10(WaylandHdr.EXTRA),
            visible = { env, values -> env.wayland(values) },
            disabled = { env, _ -> runCatching { WaylandHdr.unavailableReason(env.context) }.getOrNull() },
            worlds = WINE_LINUX,
        ),
        // A game's TV and external display (the classic TV tab).
        *TvRows.defs().toTypedArray(),

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
            // Leaving VEGAS drops the VEGAS config file from the wrapper config, as the classic editors do.
            vm = VmBinding(
                get = { _, vm -> idOf(vm.selectedDXWrapper) },
                set = { _, vm, v ->
                    val wasVegas = idOf(vm.selectedDXWrapper).contains("vegas")
                    vm.selectedDXWrapper = displayFor(v, vm.dxWrapperEntries)
                    if (wasVegas && !v.contains("vegas")) vm.dxWrapperConfig = stripVegasConfig(vm.dxWrapperConfig)
                },
            ),
            game = GameBinding(
                keys = listOf("dxwrapper"),
                read = { e -> e.get("dxwrapper")?.takeIf { it.isNotEmpty() } },
                write = { e, v ->
                    val wasVegas = (e.get("dxwrapper")?.takeIf { it.isNotEmpty() } ?: e.container.getDXWrapper() ?: "").contains("vegas")
                    e.put("dxwrapper", v)
                    if (wasVegas && !v.contains("vegas")) {
                        val config = e.get("dxwrapperConfig")?.takeIf { it.isNotEmpty() } ?: e.container.getDXWrapperConfig() ?: ""
                        val stripped = stripVegasConfig(config)
                        if (stripped != config) e.put("dxwrapperConfig", stripped)
                    }
                },
            ),
        ),
        // Every choice of the DX Wrapper Config dialogs (DXVK, VEGAS, WineD3D), for the wrapper picked above.
        *GraphicsRows.dxDefs().toTypedArray(),
        SettingDef(
            id = "graphics.unrealHdr", category = "graphics", group = "Unreal Engine", level = ADVANCED,
            label = "Unreal Engine HDR fix", hint = "The DirectX 11 fix pretends to be an NVIDIA GPU.", tech = "Unreal Engine HDR",
            help = UnrealHdr.HELP_SHORT,
            control = { _, _ -> seg(UnrealHdr.OFF to "Off", UnrealHdr.DX12 to "DirectX 12 fix", UnrealHdr.DX11 to "DirectX 11 (NVAPI)") },
            vm = vmString({ it.unrealHdr }, { vm, v -> vm.unrealHdr = v }),
            game = key(UnrealHdr.EXTRA) { UnrealHdr.normalize(it).ifEmpty { null } },
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
            id = ID_DRIVER_VERSION, category = "graphics", group = "GPU driver", level = SIMPLE,
            label = "Graphics driver", hint = "The installed Turnip the Wayland compositor puts the game's frames on screen with.", tech = "Compositor driver (graphicsDriverConfig version)",
            help = "On Wayland the game renders through the embedded compositor, which needs an installed Turnip to import its frames. \"System\" is offered only on Mali GPUs; elsewhere it shows a black screen. What the game itself renders on is the Wayland game driver below.",
            control = { env, values -> compositorControl(env, values(ID_DRIVER_VERSION)) },
            vm = compositorVm,
            game = compositorGame,
            visible = { env, values -> !env.isLinuxEntry && env.wayland(values) },
            note = { env, values -> compositorNote(env, values(ID_DRIVER_VERSION)) },
        ),
        // The Wayland driver gear: the Wayland game driver and the Mali (PanVK) switches.
        *GraphicsRows.waylandDefs().toTypedArray(),
        // The X11 driver configuration: driver version, Vulkan, extensions, GPU name, BCn, Turnip (GPU name,
        // memory, present mode and the OneUI fix also on Wayland).
        *GraphicsRows.driverDefs().toTypedArray(),
        // The whole driver config, for the switches an imported wrapper carries (DeckWrapperDetectedSection).
        *WrapperDetectedRows.defs().toTypedArray(),
        SettingDef(
            id = "graphics.fastOpenGL", category = "graphics", group = "OpenGL", level = ADVANCED,
            label = "Fast OpenGL", hint = FastOpenGL.HINT, tech = FastOpenGL.EXTRA,
            help = "OpenGL games (and DirectDraw / older Direct3D through WineD3D) run on the bundled Mesa and Zink and are presented by Vulkan, instead of a slow copy per frame. Needs a Turnip graphics driver on X11. Always on with Wayland.",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.fastOpenGL }, { vm, v -> vm.fastOpenGL = v }),
            game = key10(FastOpenGL.EXTRA),
            // Locked on with Wayland and greyed on X11 when the driver or the build can't run it; the stored choice is kept.
            disabled = { env, values ->
                if (env.wayland(values)) FastOpenGL.WAYLAND_ALWAYS_ON else env.fastOpenGLReason(values(ID_DRIVER_VERSION))
            },
        ),
        SettingDef(
            id = "graphics.glFpsHud", category = "graphics", group = "OpenGL", level = ADVANCED,
            label = FastOpenGL.FPS_HUD.title, hint = FastOpenGL.FPS_HUD.hint, tech = FastOpenGL.FPS_HUD.extra,
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.glFpsHud }, { vm, v -> vm.glFpsHud = v }),
            game = key10(FastOpenGL.FPS_HUD.extra),
        ),
        SettingDef(
            id = "graphics.glVsyncOff", category = "graphics", group = "OpenGL", level = ADVANCED,
            label = FastOpenGL.VSYNC_OFF.title, hint = FastOpenGL.VSYNC_OFF.hint, tech = FastOpenGL.VSYNC_OFF.extra,
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.glVsyncOff }, { vm, v -> vm.glVsyncOff = v }),
            game = key10(FastOpenGL.VSYNC_OFF.extra),
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
            worlds = WINE_LINUX,
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
            worlds = WINE_LINUX,
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
            id = "performance.fgCapture", category = "performance", group = "Frame generation", level = EXPERT,
            label = "Capture resolution", hint = "Height the frame-generation chain runs at; the width follows your screen. Lower is much cheaper, softer.", tech = "fgCaptureResolution",
            control = { env, _ ->
                val heights = (env.vm?.screenSizeEntries ?: emptyList())
                    .filterNot { it.equals("custom", ignoreCase = true) }
                    .mapNotNull { entry -> Container.fgCaptureHeightFor(idOf(entry)).takeIf { it > 0 }?.let { Opt("$it", prettyResolution(entry)) } }
                    .distinctBy { it.value }
                Control.Select(
                    options = listOf(Opt(Container.FG_CAPTURE_PANEL, "Panel (default)"), Opt(Container.FG_CAPTURE_GAME, "Game resolution")) + heights,
                    customLabel = "Custom height…",
                    customHint = "A height in pixels, e.g. 540",
                    validateCustom = { t -> if ((t.trim().toIntOrNull() ?: 0) > 0) null else "Type a height in pixels." },
                    normalizeCustom = { it.trim() },
                )
            },
            vm = VmBinding(
                get = { _, vm -> vm.resolvedFgCaptureResolution() },
                set = { _, vm, v ->
                    if (v == Container.FG_CAPTURE_PANEL || v == Container.FG_CAPTURE_GAME) {
                        vm.fgCaptureSelection = v
                    } else v.toIntOrNull()?.takeIf { it > 0 }?.let { h ->
                        val match = vm.screenSizeEntries.firstOrNull {
                            !it.equals("custom", ignoreCase = true) && Container.fgCaptureHeightFor(idOf(it)) == h
                        }
                        if (match != null) {
                            vm.fgCaptureSelection = match
                        } else {
                            vm.fgCaptureSelection = vm.screenSizeEntries.firstOrNull { it.equals("custom", ignoreCase = true) } ?: "Custom"
                            vm.fgCaptureCustomHeight = h.toString()
                        }
                    }
                },
            ),
            showInGame = false,
            visible = { _, values -> FeatureFlags.LSFG_NATIVE_EXPERIMENTS_ENABLED && values("performance.frameGen") == "lsfg-native" },
        ),
        SettingDef(
            id = "performance.lsfgVk11", category = "performance", group = "Frame generation", level = EXPERT,
            label = "LSFG on Vulkan 1.1 drivers (compat)", hint = "Lets LSFG Native run on drivers that only report Vulkan 1.1 but have the needed extensions. Applies to per-game overrides too.", tech = "lsfgVk11Compat",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.lsfgVk11Compat }, { vm, v -> vm.lsfgVk11Compat = v }),
            showInGame = false,
            visible = { _, _ -> FeatureFlags.LSFG_NATIVE_EXPERIMENTS_ENABLED },
            disabled = { env, values -> if (env.wayland(values)) "Not used on Wayland: frame generation runs in the compositor on your Turnip driver." else null },
        ),
        SettingDef(
            id = "performance.syncMode", category = "performance", group = "Wine sync", level = ADVANCED,
            label = "Sync", hint = "How Wine waits between threads. esync is fast and well tested; ntsync is closest to Windows; wineserver is slowest and most compatible.", tech = "syncMode",
            help = "esync is the layer default. ntsync is new: try it per game. wineserver sends every wait through the wineserver, for games that misbehave. fsync can't run on Android. A choice the layer can't run falls back to its default.",
            control = { env, _ -> Control.Segmented(SyncMode.ALL.map { m -> Opt(m, m, disabledReason = syncReason(env, m)) }) },
            vm = vmString({ it.syncMode }, { vm, v -> vm.onSyncModePicked(v) }),
            // A game stores a mode only when it differs from the container's; picking the container's follows it again.
            game = GameBinding(
                keys = listOf(SyncMode.EXTRA),
                read = { e -> SyncSupport.storedMode(e.get(SyncMode.EXTRA), e.get("envVars")) },
                write = { e, v ->
                    val vm = e.env.vm
                    val resolved = vm?.syncCaps?.resolve(v) ?: v
                    e.put(SyncMode.EXTRA, resolved.takeIf { it != vm?.syncMode })
                    stripGameSyncVars(e)
                },
                reset = { e -> e.put(SyncMode.EXTRA, null); stripGameSyncVars(e) },
            ),
            onDefaults = false,
            note = { env, values ->
                val vm = env.vm
                if (env.scope == EditorScope.GAME) {
                    val picked = values("performance.syncMode")
                    val caps = vm?.syncCaps
                    if (caps != null && !caps.isAvailable(picked)) "$picked isn't in ${vm?.selectedWineVersion.orEmpty()}: this game runs ${caps.resolve(picked)}." else null
                } else vm?.syncNotice
            },
        ),
        SettingDef(
            id = "performance.translator", category = "performance", group = "x86 translators", level = ADVANCED,
            label = "x86 translator for 32-bit code", hint = "Runs the game's 32-bit x86 code on your ARM CPU.", tech = "Emulator (32-bit)",
            control = { env, _ ->
                val labels = mapOf("fexcore" to "FEXCore", "box64" to "WOWBox64")
                Control.Segmented((env.vm?.emulatorEntries ?: emptyList()).map { Opt(idOf(it), labels[idOf(it)] ?: it) })
            },
            vm = VmBinding(get = { _, vm -> idOf(vm.selectedEmulator) }, set = { _, vm, v -> vm.selectedEmulator = displayFor(v, vm.emulatorEntries) }),
            game = key("emulator") { it.lowercase(Locale.ENGLISH) },
            visible = { env, _ -> env.arm64ec },
        ),
        SettingDef(
            id = "performance.translator64", category = "performance", group = "x86 translators", level = ADVANCED,
            label = "x86 translator for 64-bit code", hint = "Runs the game's 64-bit x86 code. Box64EC needs a Box64EC component installed.", tech = "Emulator (64-bit)",
            control = { env, _ ->
                Control.Segmented(listOf(
                    Opt("fexcore", "FEXCore"),
                    Opt("box64ec", "Box64EC", disabledReason = if (env.box64ECInstalled) null else "Install a Box64EC component first."),
                ))
            },
            vm = VmBinding(get = { _, vm -> idOf(vm.selectedEmulator64) }, set = { _, vm, v -> vm.selectedEmulator64 = displayFor(v, vm.emulator64Entries) }),
            game = key("emulator64") { it.lowercase(Locale.ENGLISH) },
            // A game is offered the choice once a Box64EC is installed; until then it follows the container (FEXCore).
            visible = { env, _ -> env.arm64ec && (env.scope != EditorScope.GAME || env.box64ECInstalled) },
        ),
        SettingDef(
            id = "performance.fexVersion", category = "performance", group = "FEXCore", level = ADVANCED,
            label = "FEX version", tech = "FEXCore Version",
            control = { env, _ -> Control.Select((env.vm?.fexCoreVersionEntries ?: emptyList()).map { Opt(it, it) }) },
            vm = vmString({ it.selectedFEXCoreVersion }, { vm, v -> vm.selectedFEXCoreVersion = v }),
            game = key("fexcoreVersion"),
            visible = { env, _ -> env.arm64ec },
        ),
        // The Linux runtime brings its own FEX, but hands this preset to the games the Steam client launches.
        SettingDef(
            id = "performance.fexPreset", category = "performance", group = "FEXCore", level = ADVANCED,
            label = "FEX preset", hint = "Trade speed for compatibility. Edit its values under Translator preset values below.", tech = "FEXCore Preset",
            control = { env, _ -> Control.Select(env.fexPresets.map { Opt(it.id, it.name) }) },
            vm = VmBinding(get = { _, vm -> vm.selectedFEXCorePresetId }, set = { _, vm, v -> vm.selectFEXCorePresetById(v) }),
            game = key("fexcorePreset"),
            visible = { env, _ -> env.isLinuxEntry || env.arm64ec },
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = "performance.forceSsbs", category = "performance", group = "FEXCore", level = ADVANCED,
            label = "Force SSBS (CPU speed fix)", hint = "Keeps the CPU's speculative-store fast path on for game threads (arm64ec Proton only). On by default.", tech = "forceSsbs",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.forceSsbs }, { vm, v -> vm.forceSsbs = v }),
            game = key10("forceSsbs"),
            visible = { env, _ -> env.arm64ec },
        ),
        SettingDef(
            id = "performance.box64Version", category = "performance", group = "Box64 / WOWBox64", level = ADVANCED,
            label = "Box64 version", hint = "Called WOWBox64 on ARM64EC containers, where it runs 32-bit code when chosen above.", tech = "Box64 Version / WOWBox64 Version",
            control = { env, _ -> Control.Select((env.vm?.box64VersionEntries ?: emptyList()).map { Opt(it, it) }) },
            vm = vmString({ it.selectedBox64Version }, { vm, v -> vm.selectedBox64Version = v }),
            game = key("box64Version"),
        ),
        SettingDef(
            id = "performance.box64Preset", category = "performance", group = "Box64 / WOWBox64", level = ADVANCED,
            label = "Box64 preset", hint = "Its BOX64_* variables apply to Box64EC too.", tech = "Box64 Preset / WOWBox64 Preset",
            control = { env, _ -> Control.Select(env.box64Presets.map { Opt(it.id, it.name) }) },
            vm = VmBinding(get = { _, vm -> vm.selectedBox64PresetId }, set = { _, vm, v -> vm.selectBox64PresetById(v) }),
            game = key("box64Preset"),
        ),
        SettingDef(
            id = "performance.box64ecVersion", category = "performance", group = "Box64EC", level = ADVANCED,
            label = "Box64EC version", hint = "Runs 64-bit games when the 64-bit translator is Box64EC.", tech = "Box64EC Version",
            control = { env, _ -> Control.Select((env.vm?.box64ECVersionEntries ?: emptyList()).map { Opt(it, it) }) },
            vm = vmString({ it.selectedBox64ECVersion }, { vm, v -> vm.selectedBox64ECVersion = v }),
            game = key("box64ecVersion"),
            visible = { env, _ -> env.arm64ec },
            disabled = { env, _ -> if (env.box64ECInstalled) null else "No Box64EC installed. Download one below (Translator downloads)." },
        ),
        SettingDef(
            id = "performance.startupServices", category = "performance", group = "Windows background services", level = ADVANCED,
            label = "Services started with Windows", hint = "Fewer services = faster start and more free RAM.", tech = "Startup Selection",
            control = { _, _ -> seg("0" to "All", "1" to "Essential only", "2" to "Stop at startup", "3" to "Custom") },
            vm = vmInt({ it.selectedStartupSelection }, { vm, v -> vm.selectedStartupSelection = v }),
            game = intKey("startupSelection"),
        ),
        SettingDef(
            id = "performance.cpuAffinity", category = "performance", group = "CPU cores", level = ADVANCED,
            label = "Cores the game may use", hint = "At least one core stays on.", tech = "Processor Affinity (cpuList)",
            control = { _, _ -> Control.Cores },
            vm = vmString({ it.cpuList }, { vm, v -> vm.cpuList = v }),
            game = key("cpuList"),
        ),
        SettingDef(
            id = "performance.cpuAffinity32", category = "performance", group = "CPU cores", level = EXPERT,
            label = "Cores 32-bit programs may use", hint = "The same pick for 32-bit (WOW64) programs.", tech = "Processor Affinity (32-bit apps) (cpuListWoW64)",
            control = { _, _ -> Control.Cores },
            vm = vmString({ it.cpuListWoW64 }, { vm, v -> vm.cpuListWoW64 = v }),
            showInGame = false,
            visible = { env, _ -> env.arm64ec },
        ),
        perfToggle("performance.power.sustained", "sustainedPerfMode", "Sustained performance mode", "Keeps clocks steady for long sessions instead of short bursts."),
        perfToggle("performance.power.priorityBoost", "perfPriorityBoost", "Boost game thread priority", null),
        perfToggle("performance.power.bigCores", "preferBigCores", "Prefer big CPU cores", null),
        // Custom services, the GPU clock lock and root switches, and the preset values' hidden rows.
        *PerformanceRows.defs().toTypedArray(),

        // Visual effects (per game only: the container has no vkBasalt setting)
        SettingDef(
            id = "visuals.vkbasalt", category = "visuals", group = "Sharpening", level = ADVANCED,
            label = "Vulkan post-effect (vkBasalt)", hint = "Sharpens the image after it is drawn.", tech = "Sharpness (VKBasalt) › Effect",
            control = { _, _ -> seg("None" to "None", "CAS" to "CAS sharpen", "DLS" to "DLS sharpen + denoise") },
            game = GameBinding(listOf("sharpnessEffect"), read = { e -> e.get("sharpnessEffect")?.takeIf { it.isNotEmpty() } },
                write = { e, v -> e.put("sharpnessEffect", v) }, inherit = Inherit.BUILT_IN, inherited = { "None" }),
            visible = { env, _ -> gameScope(env) },
        ),
        percentSlider("visuals.vkbasaltLevel", "sharpnessLevel", "vkBasalt strength", "Level") { values -> values("visuals.vkbasalt") != "None" },
        percentSlider("visuals.vkbasaltDenoise", "sharpnessDenoise", "vkBasalt denoise", "Denoise") { values -> values("visuals.vkbasalt") == "DLS" },
        // ReShade: Solo / Stack and the loadout the section edits.
        *ReshadeRows.defs().toTypedArray(),

        // Audio
        SettingDef(
            id = "audio.driver", category = "audio", group = "Sound", level = SIMPLE,
            label = "Sound system", hint = "PulseAudio suits most games. DirectAudio has the lowest delay on the builds that ship it.", tech = "Audio Driver",
            control = { env, _ ->
                val labels = mapOf("alsa" to "ALSA", "pulseaudio" to "PulseAudio", "directaudio" to "DirectAudio")
                // A Linux entry's DirectAudio is the runtime's own relay driver, so the container's layer says nothing about it.
                val supported = env.scope == EditorScope.DEFAULTS || env.isLinuxEntry ||
                    env.vm?.let { DirectAudioSupport.isSupported(it.selectedWineVersion) } == true
                Control.Segmented((env.vm?.audioDriverEntries ?: emptyList()).map { entry ->
                    val id = idOf(entry)
                    Opt(id, labels[id] ?: entry,
                        disabledReason = if (id == "directaudio" && !supported) "Needs Proton ${DirectAudioSupport.SUPPORTED_LABEL}." else null)
                })
            },
            vm = VmBinding(get = { _, vm -> idOf(vm.selectedAudioDriver) }, set = { _, vm, v -> vm.selectedAudioDriver = displayFor(v, vm.audioDriverEntries) }),
            game = key("audioDriver"),
            confirm = { v ->
                if (v == "directaudio") Confirm(
                    "DirectAudio is experimental",
                    "It sends the game's sound straight to Android's audio system with no PulseAudio or ALSA server in between: the lowest-latency path. Because it's new, some games may skip now and then, or a preset may not stick; the Auto preset is the smoothest. If you hit audio problems, switch back to PulseAudio or ALSA.",
                    "Use DirectAudio",
                ) else null
            },
            worlds = WINE_LINUX,
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
        ),
        // Sound latency preset, the microphone, and the env string the fine-tune dialog edits.
        *AudioRows.defs().toTypedArray(),
        // Expert: the same env string, every variable of it (DeckEnvVarsSection).
        *EnvVarRows.defs().toTypedArray(),

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
            visible = { env, _ -> gameScope(env) },
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = "input.dinputVisible", category = "input", group = "Controllers", level = ADVANCED,
            label = "DirectInput games can see the controller", hint = "Off by default, so games that read both XInput and DirectInput see one pad.",
            help = "Lets DirectInput games (many older titles, racing games, wheels) see the controller. Off hides it from DirectInput so games that support both XInput and DirectInput don't see it twice. The Classic DirectInput pad identity always turns this on.",
            tech = "Exclusive Input / Enable DInput",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.dinputVisible }, { vm, v -> vm.dinputVisible = v }),
            // Stored in the game's inputType / exclusiveXInput extras, which older builds read too
            // (DInputVisibility maps their old combinations); Reset removes both.
            game = GameBinding(
                listOf(DInputVisibility.EXTRA_EXCLUSIVE, DInputVisibility.EXTRA_INPUT_TYPE),
                read = { e ->
                    val exclusive = e.get(DInputVisibility.EXTRA_EXCLUSIVE)
                    val inputType = e.get(DInputVisibility.EXTRA_INPUT_TYPE)
                    if (exclusive.isNullOrEmpty() && inputType.isNullOrBlank()) null
                    else onOff(DInputVisibility.fromExtras(exclusive, inputType, e.container))
                },
                write = { e, v ->
                    e.put(DInputVisibility.EXTRA_EXCLUSIVE, "1")
                    e.put(DInputVisibility.EXTRA_INPUT_TYPE, "${DInputVisibility.inputTypeFor(v == "1")}")
                },
            ),
        ),
        SettingDef(
            id = "input.bannerinXInput", category = "input", group = "Controllers", level = ADVANCED,
            label = "Direct XInput (BANNERIN)", hint = "XInput games read the controller directly, with no input queue (no stale stick after release). Off by default.", tech = "bannerinXInput",
            help = "XInput games read the controller straight from Bannerlator instead of through Wine's controller queue: always the live stick position, no added delay, rumble included. Off: XInput goes through Wine as before. If a game misbehaves, turn it off for that game.",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.bannerinXInput }, { vm, v -> vm.bannerinXInput = v }),
            game = key10("bannerinXInput"),
        ),
        SettingDef(
            id = "input.bannerinXInputSwitching", category = "input", group = "Controllers", level = ADVANCED,
            label = "Allow in-game switching", hint = "Switch Direct XInput on or off from the in-game menu without restarting.",
            help = "Installs Bannerlator's XInput DLLs whether Direct XInput is on or off, with Wine's own XInput beside them, so Controls › Pad in the in-game menu can switch between them while the game runs (the controller reconnects). On by default. Off: Direct XInput stays as the game started, and changing it needs a restart.",
            tech = BannerinXInput.EXTRA_SWITCHING,
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.bannerinXInputSwitching }, { vm, v -> vm.bannerinXInputSwitching = v }),
            game = key10(BannerinXInput.EXTRA_SWITCHING),
        ),
        SettingDef(
            id = "input.stickPacing", category = "input", group = "Controllers", level = ADVANCED,
            label = "Stick updates", hint = "How often stick movement reaches the game. Also changeable in-game.",
            help = "Auto (default): one stick update per frame the game draws, up to the limit set (60 per second by default), so a game that draws faster than it reads input never falls behind. Fixed: that many updates per second. Unpaced: every change goes out at once. Applies at once, also in-game (Controls › Pad). BANNERIN_AXIS_HZ / BANNERIN_AXIS_AUTO_MAX in the env vars win over this setting.",
            tech = StickPacing.EXTRA,
            control = { _, _ ->
                Control.Select(
                    listOf(
                        Opt("auto:60", "Auto (up to 60/s)"), Opt("auto:120", "Auto (up to 120/s)"), Opt("auto:30", "Auto (up to 30/s)"),
                        Opt("fixed:60", "Fixed 60/s"), Opt("fixed:125", "Fixed 125/s"), Opt("fixed:250", "Fixed 250/s"),
                        Opt("off", "Unpaced"),
                    ),
                    customLabel = "Custom…", customHint = "auto:N (20–240), fixed:N (30–1000) or off",
                    validateCustom = { v -> if (StickPacing.parse(v) == null) "Use auto:N, fixed:N or off" else null },
                    normalizeCustom = { v -> StickPacing.parse(v)?.format() ?: v.trim() },
                )
            },
            vm = vmString({ it.stickPacing }, { vm, v -> vm.stickPacing = StickPacing.parse(v)?.format() ?: StickPacing.DEFAULT.format() }),
            game = key(StickPacing.EXTRA) { v -> StickPacing.parse(v)?.format() },
            disabled = { env, _ ->
                if (StickPacing.envOverrides(env.vm?.container, env.shortcut)) "Set by BANNERIN_AXIS_HZ / BANNERIN_AXIS_AUTO_MAX in the env vars." else null
            },
        ),
        SettingDef(
            id = "input.controllerIdentity", category = "input", group = "Controllers", level = ADVANCED,
            label = "Controller identity", hint = "Which controller the game sees. Also changeable in-game (the controller reconnects).",
            help = "Generic (default): works with everything, same as before. Xbox 360 / One / Series: games treat the pad as an Xbox controller; best paired with Direct XInput for live input and rumble. Classic DirectInput pad: for old games that don't accept Xbox pads; it always lets DirectInput games see the controller. PlayStation / Nintendo / Joy-Con / Steam Deck (experimental): games may show those button icons; no gyro, touchpad, light bar or trackpads, and a few games may not detect the pad. Switch back to Generic or Xbox if so.\n\nIf the controller disappears in a game, switch back to Generic.",
            tech = ControllerIdentity.ENV,
            control = { _, _ -> Control.Select(ControllerIdentity.wineChoices().map { Opt(it.value, it.label) }) },
            vm = vmString({ ControllerIdentity.forWine(it.controllerIdentity) }, { vm, v -> vm.controllerIdentity = ControllerIdentity.forWine(v) }),
            // Only values a Wine game can use: a Linux-only one (e.g. from an imported config) reads as unset.
            game = key(ControllerIdentity.EXTRA) { v -> ControllerIdentity.normalize(v)?.takeIf { it == ControllerIdentity.forWine(it) } },
        ),
        SettingDef(
            id = "input.disableXInput", category = "input", group = "Controllers", level = EXPERT,
            label = "Disable XInput", hint = "Hides Xbox controllers from this game.", tech = "disableXinput",
            control = { _, _ -> Control.Toggle },
            game = GameBinding(
                listOf("disableXinput"),
                read = { e -> e.get("disableXinput")?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
                write = { e, v -> e.put("disableXinput", if (v == "1") "1" else null) },
                inherit = Inherit.BUILT_IN, inherited = { "0" },
            ),
            visible = { env, _ -> gameScope(env) },
        ),
        SettingDef(
            id = "input.numControllers", category = "input", group = "Controllers", level = ADVANCED,
            label = "Number of controllers", hint = "How many pads the game is offered.", tech = "Num Controllers",
            control = { _, _ -> seg("1" to "1", "2" to "2", "3" to "3", "4" to "4") },
            game = GameBinding(
                listOf("numControllers"),
                read = { e -> e.get("numControllers")?.trim()?.toIntOrNull()?.coerceIn(1, 4)?.toString() },
                write = { e, v -> e.put("numControllers", v) },
                inherit = Inherit.BUILT_IN, inherited = { "1" },
            ),
            visible = { env, _ -> gameScope(env) },
        ),
        SettingDef(
            id = "input.touchMode", category = "input", group = "Controllers", level = ADVANCED,
            label = "Touch acts as", hint = "Touchscreen sends real touches to the game.", tech = "Touchscreen Mode",
            control = { _, _ -> seg("0" to "Mouse / touchpad", "1" to "Touchscreen") },
            game = builtIn10("simTouchScreen", false),
            visible = { env, _ -> gameScope(env) },
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = "input.hideOnController", category = "input", group = "Player slots", level = SIMPLE,
            label = "Hide on-screen controls when a controller connects",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.autoHideControlsOnPad }, { vm, v -> vm.autoHideControlsOnPad = v }),
            game = key10("autoHideControlsOnPad"),
            worlds = WINE_LINUX,
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
            visible = { env, _ -> gameScope(env) },
        ),
        SettingDef(
            id = "input.mouseWarp", category = "input", group = "Mouse & touch", level = EXPERT,
            label = "Mouse capture (DirectInput)", hint = "Fixes spinning or stuck cameras in some older games.", tech = "Mouse Warp Override",
            control = { _, _ -> seg("0" to "Off", "1" to "On", "2" to "Forced") },
            vm = vmInt({ it.selectedMouseWarpIndex }, { vm, v -> vm.selectedMouseWarpIndex = v }),
            onDefaults = false, showInGame = false,
            visible = { env, _ -> env.scope == EditorScope.CONTAINER },
        ),
        SettingDef(
            id = "input.vibrationTarget", category = "input", group = "Rumble", level = SIMPLE,
            label = "Rumble on", tech = "Vibration mode",
            help = "Controller: vibrates the gamepad. Device: vibrates the handheld/phone (on some devices, e.g. AYANEO, the system routes this to the gamepad anyway). Both / Off: as named.",
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
            help = "Scales vibration strength. Note: on some handhelds a lower strength does not reduce stick slowdown. Use Rumble motors: Single for that.",
            control = { _, _ -> Control.Slider(0f, 100f, 5f, { "${it.toInt()}%" }, { "${it.toInt()}" }) },
            vm = vmInt({ it.vibrationIntensity }, { vm, v -> vm.vibrationIntensity = v }),
            game = intKey("vibrationIntensity"),
            visible = { _, values -> values("input.vibrationTarget") != "${Container.VIBRATION_MODE_OFF}" },
        ),
        SettingDef(
            id = "input.rumbleMotors", category = "input", group = "Rumble", level = ADVANCED,
            label = "Rumble motors", hint = "Single keeps sticks smooth on handhelds that slow down while both motors run.",
            help = "Both (PC-accurate): drives the big and small motors separately, like a PC controller. Single (smoother sticks): uses one motor. Some handheld controllers (e.g. AYANEO Pocket FIT) slow their stick updates while both motors run; Single halves that slowdown so aiming stays smooth. Default: Single on the Pocket FIT, Both elsewhere.",
            tech = RumbleMotors.EXTRA,
            control = { _, _ -> seg("${RumbleMotors.BOTH}" to "Both (PC-accurate)", "${RumbleMotors.SINGLE}" to "Single (smoother sticks)") },
            vm = vmInt({ it.rumbleMotors }, { vm, v -> vm.rumbleMotors = RumbleMotors.sanitize(v) }),
            game = key(RumbleMotors.EXTRA) { v -> RumbleMotors.parse(v)?.toString() },
            // Only a controller has two motors (the phone's vibrator is one).
            visible = { _, values ->
                values("input.vibrationTarget") == "${Container.VIBRATION_MODE_CONTROLLER}" ||
                    values("input.vibrationTarget") == "${Container.VIBRATION_MODE_BOTH}"
            },
        ),
        SettingDef(
            id = "input.gyro.enabled", category = "input", group = "Motion aim (gyro)", level = SIMPLE,
            label = "Motion aim", hint = "Tilt the device to aim.", tech = "Enable Motion Aim",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.gyroEnabled }, { vm, v -> vm.gyroEnabled = v }),
            game = key10("gyroEnabled"),
            worlds = WINE_LINUX,
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
            worlds = WINE_LINUX,
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
            worlds = WINE_LINUX,
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
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = "input.gyro.activation", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Button behaviour", tech = "Activation",
            control = { _, _ -> seg("${Container.GYRO_ACTIVATION_HOLD}" to "Hold", "${Container.GYRO_ACTIVATION_TOGGLE}" to "Toggle") },
            vm = vmInt({ it.gyroActivationMode }, { vm, v -> vm.gyroActivationMode = v }),
            game = intKey("gyroActivationMode"),
            visible = { _, values -> values("input.gyro.enabled") == "1" && values("input.gyro.activator") != "${Container.GYRO_ACTIVATOR_ALWAYS}" },
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = "input.gyro.sensitivity", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Sensitivity", tech = "Sensitivity",
            control = { _, _ -> Control.Slider(0.1f, 10f, 0.1f, { fmt1(it) }, { fmt1(it) }) },
            vm = VmBinding(get = { _, vm -> fmt1(vm.gyroSensitivity) }, set = { _, vm, v -> v.toFloatOrNull()?.let { vm.gyroSensitivity = it } }),
            game = key("gyroSensitivity") { v -> v.toFloatOrNull()?.let { fmt1(it) } },
            visible = { _, values -> values("input.gyro.enabled") == "1" },
            worlds = WINE_LINUX,
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
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = "input.gyro.invertY", category = "input", group = "Motion aim (gyro)", level = ADVANCED,
            label = "Invert up/down", tech = "Invert Y",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.gyroInvertY }, { vm, v -> vm.gyroInvertY = v }),
            game = key10("gyroInvertY"),
            visible = { _, values -> values("input.gyro.enabled") == "1" },
            worlds = WINE_LINUX,
        ),
        // Player slot pins (edited by DeckInputSection) and the XR controller mapping.
        *InputRows.defs().toTypedArray(),

        // Performance overlay: the launch path reads the on/off switch from the container only.
        SettingDef(
            id = "hud.show", category = "hud", group = "Overlay", level = SIMPLE,
            label = "Show performance overlay", hint = "FPS, CPU/GPU load and temperatures on top of the game. Its style is below, and in-game from the side menu.", tech = "Show FPS",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.showFPS }, { vm, v -> vm.showFPS = v }),
        ),
        // Every choice of the classic FPS counter dialog.
        *OverlayRows.defs().toTypedArray(),

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
        ),
        SettingDef(
            id = "windows.closeOnExit", category = "windows", group = "System", level = ADVANCED,
            label = "Close the session when the game exits", tech = "Close when game exits",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.autoCloseOnExit }, { vm, v -> vm.autoCloseOnExit = v }),
            game = key10("autoCloseOnExit"),
        ),
        SettingDef(
            id = "windows.runAsAdmin", category = "windows", group = "System", level = ADVANCED,
            label = "Run programs as administrator", hint = "Avoids permission prompts inside Windows (turns UAC off).", tech = "Run as administrator (EnableLUA)",
            control = { _, _ -> Control.Toggle },
            vm = vmBool({ it.runAsAdmin }, { vm, v -> vm.runAsAdmin = v }),
            showInGame = false,
        ),
        SettingDef(
            id = "windows.theme", category = "windows", group = "Windows desktop", level = ADVANCED,
            label = "Desktop theme", tech = "Theme",
            control = { _, _ -> seg("0" to "Light", "1" to "Dark") },
            vm = vmInt({ it.desktopThemeIndex }, { vm, v -> vm.desktopThemeIndex = v }),
            showInGame = false,
        ),
        // The Wine layer, the desktop background and colour, the wallpaper's scope.
        *WindowsRows.defs().toTypedArray(),

        // Windows components: the classic Win Components tab's choices. The install lists around them are
        // not settings; the editor's host adds them (DeckComponentsPage).
        dllRow("components.direct3d", "direct3d", "Direct3D", "DirectX"),
        dllRow("components.directsound", "directsound", "DirectSound", "DirectX"),
        dllRow("components.directmusic", "directmusic", "DirectMusic", "DirectX"),
        dllRow("components.directshow", "directshow", "DirectShow", "DirectX"),
        dllRow("components.directplay", "directplay", "DirectPlay", "DirectX"),
        dllRow("components.xaudio", "xaudio", "XAudio", "Audio and runtimes"),
        dllRow("components.vcrun2010", "vcrun2010", "Visual C++ 2010", "Audio and runtimes"),
        codecRow("windows.codecBackend", MediaDecoder.KEY_BACKEND, "Video decoder",
            "Which backend Media Foundation plays cutscenes with. FFmpeg needs an arm64ec layer built with it (Proton 11.0-2 vc12+); elsewhere it falls back to GStreamer."),
        codecRow("windows.softwareDecoding", MediaDecoder.KEY_SOFTWARE_DECODING, "Software video decoding",
            "Keeps video frames off the GPU. Try it for black video with sound."),

        // Linux runtime: the Linux Steam client entry only (the classic game editor's isLinuxEntry rows).
        SettingDef(
            id = "linux.displayDriver", category = "linux", group = "Drivers", level = SIMPLE,
            label = "Display driver (Android side)", hint = "The driver the app's compositor puts the session on screen with. Not what the client draws with.", tech = "Compositor driver (graphicsDriverConfig version)",
            control = { env, values -> compositorControl(env, values(ID_DRIVER_VERSION)) },
            vm = compositorVm,
            game = compositorGame,
            visible = { env, _ -> gameScope(env) },
            note = { env, values -> compositorNote(env, values(ID_DRIVER_VERSION)) },
            worlds = LINUX_ONLY,
        ),
        SettingDef(
            id = "linux.drawDriver", category = "linux", group = "Drivers", level = SIMPLE,
            label = "Draw driver (Linux runtime)", hint = LinuxVulkanDriver.HELP_TEXT, tech = LinuxVulkanDriver.EXTRA,
            control = { env, values ->
                val current = values("linux.drawDriver")
                val known = env.linuxDrawDrivers
                Control.Select(
                    known.map { (v, label) -> Opt(v, label) } +
                        (if (known.none { it.first == current }) listOf(Opt(current, LinuxVulkanDriver.optionLabel(env.context, current))) else emptyList()),
                )
            },
            game = GameBinding(
                listOf(LinuxVulkanDriver.EXTRA),
                read = { e -> e.get(LinuxVulkanDriver.EXTRA)?.takeIf { it.isNotEmpty() } },
                write = { e, v -> e.put(LinuxVulkanDriver.EXTRA, v.ifEmpty { null }) },
                inherit = Inherit.BUILT_IN, inherited = { "" },
            ),
            visible = { env, _ -> gameScope(env) },
            note = { env, _ -> if (env.linuxDrawDrivers.size > 1) null else "Nothing imported yet: Contents › Installed › Linux runtime drivers." },
            worlds = LINUX_ONLY,
        ),
        linuxToggle("linux.glThread", LinuxTuning.EXTRA_GLTHREAD, "Performance (experimental)", "Threaded GL (mesa_glthread)", ADVANCED,
            hint = "Makes the chain that draws the client's interface cheaper. Not proven on every device: change one at a time."),
        linuxToggle("linux.lazyDescriptors", LinuxTuning.EXTRA_LAZY_DESCRIPTORS, "Performance (experimental)", "Lazy descriptors (ZINK_DESCRIPTORS)", ADVANCED),
        linuxToggle("linux.noGlError", LinuxTuning.EXTRA_NO_GL_ERROR, "Performance (experimental)", "Skip GL error checks (MESA_NO_ERROR)", ADVANCED),
        linuxToggle("linux.steamDeckMode", LinuxTuning.EXTRA_STEAMDECK, "Performance (experimental)", "Steam Deck mode (-steamdeck)", EXPERT,
            hint = "Makes the client behave as on a Steam Deck, with the Quick Access Menu. Games stop receiving the controller.",
            confirm = { v ->
                if (v == "1") Confirm(
                    "Turn on Steam Deck mode?",
                    "Deck mode makes the Steam client behave as it does on a Steam Deck and adds the Quick Access Menu.\n\n" +
                        "Known problems, found on device:\n" +
                        "• Games stop receiving the controller. Steam's own menus still work, but in a game the pad does nothing.\n" +
                        "• The client may show a Steam Client update under System that will not install. Deck mode now uses the Steam Deck beta channel to avoid it, but if it appears, do not press Apply.\n" +
                        "• The Quick Access Menu's performance overlay is new and not yet tried on a device.\n\n" +
                        "The scaling controls do the useful part without it, and the in-game drawer's FPS limit caps the frame rate. Turn Deck mode off again if a game stops responding to the controller.",
                    "Turn on anyway",
                ) else null
            },
            note = { _, values ->
                if (values("linux.steamDeckMode") == "1") "Deck mode is on: games do not receive the controller. If a Steam Client update shows under System that will not install, do not press Apply on it." else null
            },
        ),
        SettingDef(
            id = "linux.steamChannel", category = "linux", group = "Performance (experimental)", level = ADVANCED,
            label = "Steam client update channel", tech = LinuxTuning.EXTRA_STEAM_CHANNEL,
            control = { _, values ->
                Control.Select(LinuxTuning.STEAM_CHANNELS.map {
                    Opt(it, when (it) {
                        "" -> if (values("linux.steamDeckMode") == "1") "Automatic (Steam Deck beta, for Deck mode)" else "Automatic (public beta)"
                        "publicbeta" -> "Public beta"
                        else -> "Steam Deck beta"
                    })
                })
            },
            game = linuxChoice(LinuxTuning.EXTRA_STEAM_CHANNEL, LinuxTuning.STEAM_CHANNELS.toList()),
            visible = { env, _ -> gameScope(env) },
            worlds = LINUX_ONLY,
        ),
        SettingDef(
            id = "linux.scaler", category = "linux", group = "Performance (experimental)", level = ADVANCED,
            label = "Scaling mode", hint = "Only matters when a game renders below the session's resolution.", tech = LinuxTuning.EXTRA_SCALER,
            control = { _, _ -> Control.Select(LinuxTuning.SCALERS.map { Opt(it, if (it.isEmpty()) "Default" else it.replaceFirstChar(Char::uppercase)) }) },
            game = linuxChoice(LinuxTuning.EXTRA_SCALER, LinuxTuning.SCALERS.toList()),
            visible = { env, _ -> gameScope(env) },
            worlds = LINUX_ONLY,
        ),
        SettingDef(
            id = "linux.filter", category = "linux", group = "Performance (experimental)", level = ADVANCED,
            label = "Scaling filter", hint = "FSR, NIS and SGSR upscale and sharpen; SGSR is Qualcomm's, made for Adreno.", tech = LinuxTuning.EXTRA_FILTER,
            control = { _, _ ->
                Control.Select(LinuxTuning.FILTERS.map {
                    Opt(it, when (it) {
                        "" -> "Default"
                        "fsr", "nis", "sgsr" -> it.uppercase(Locale.ENGLISH)
                        else -> it.replaceFirstChar(Char::uppercase)
                    })
                })
            },
            game = linuxChoice(LinuxTuning.EXTRA_FILTER, LinuxTuning.FILTERS.toList()),
            visible = { env, _ -> gameScope(env) },
            worlds = LINUX_ONLY,
        ),
        SettingDef(
            id = "linux.touch", category = "linux", group = "Steam client", level = SIMPLE,
            label = "Touch", hint = "Touchscreen sends fingers to Steam as real touches; Touchpad moves the pointer with a drag and clicks with a tap.", tech = LinuxTuning.EXTRA_TOUCH,
            control = { _, _ ->
                Control.Segmented(LinuxTuning.TOUCH_CHOICES.map {
                    Opt(it, when (it) { "" -> "App setting"; "1" -> "Touchscreen"; else -> "Touchpad" })
                })
            },
            game = linuxChoice(LinuxTuning.EXTRA_TOUCH, LinuxTuning.TOUCH_CHOICES.toList()),
            visible = { env, _ -> gameScope(env) },
            worlds = LINUX_ONLY,
        ),
        linuxToggle("linux.steamButtons", LinuxTuning.EXTRA_STEAM_BUTTONS, "Steam client", "On-screen Steam and Quick Access buttons", SIMPLE,
            hint = "Steam's menu in the top-left corner, its Quick Access Menu in the top-right."),
        linuxToggle("linux.doubleBackQam", LinuxTuning.EXTRA_DOUBLE_BACK_QAM, "Steam client", "Double Back opens Quick Access", ADVANCED,
            hint = "One Back press still opens the in-game drawer."),
        linuxToggle("linux.fillScreen", LinuxTuning.EXTRA_FILL_SCREEN, "Steam client", "Stretch games to fill the screen", ADVANCED,
            hint = "Keeps a game that shrinks its window filling the screen."),
        linuxToggle("linux.idTech3", LinuxTuning.EXTRA_IDTECH3, "Steam client", "Quake-engine games windowed", ADVANCED,
            hint = "Quake III, Team Arena, Return to Castle Wolfenstein and Jedi Academy run windowed at the session's size."),
        linuxToggle("linux.offline", LinuxTuning.EXTRA_OFFLINE, "Steam client", "Offline mode (no sign-in to Valve)", ADVANCED,
            hint = "Installed games still launch. Needs one online sign-in on this device first. Applies at the next session."),
        linuxToggle("linux.noXalia", LinuxTuning.EXTRA_NO_XALIA, "Troubleshooting", "Xalia off (PROTON_USE_XALIA=0)", EXPERT,
            hint = "On by default: xalia costs every game CPU time under FEX. Turn it off only for a game that needs its gamepad navigation."),
        linuxToggle("linux.prootNoSeccomp", LinuxTuning.EXTRA_PROOT_NO_SECCOMP, "Troubleshooting", "proot without seccomp (PROOT_NO_SECCOMP)", EXPERT,
            hint = "Slower. Only for a device whose seccomp gets in the way."),
        SettingDef(
            id = "linux.turnipSysmem", category = "linux", group = "Troubleshooting", level = EXPERT,
            label = "Turnip sysmem rendering (TU_DEBUG=sysmem)", hint = "What the A710/A720/A722 driver builds need; Automatic turns it on for those imports. A TU_DEBUG in the environment variables wins.", tech = LinuxTuning.EXTRA_TU_SYSMEM,
            control = { _, _ ->
                Control.Segmented(LinuxTuning.TU_SYSMEM_CHOICES.map {
                    Opt(it, when (it) { "" -> "Automatic"; "1" -> "On"; else -> "Off" })
                })
            },
            game = linuxChoice(LinuxTuning.EXTRA_TU_SYSMEM, LinuxTuning.TU_SYSMEM_CHOICES.toList()),
            visible = { env, _ -> gameScope(env) },
            worlds = LINUX_ONLY,
        ),
        // The Steam client and game core lists, and the Games folders' hidden row (DeckLinuxSettings).
        *LinuxRuntimeRows.defs().toTypedArray(),
        linuxToggle("linux.appGames", LinuxTuning.EXTRA_APP_GAMES, "Your games in Steam", "Show my Games-tab games in Steam", ADVANCED,
            hint = "Games you added in the Games tab show in Steam's library under Non-Steam and run with Steam's Proton."),
        linuxToggle("linux.shareSaves", LinuxTuning.EXTRA_SHARE_SAVES, "Your games in Steam", "Share saves with the app", ADVANCED,
            hint = "The game's Documents, AppData and Saved Games folders are the ones its container uses. Don't run the same game in both at once.",
            visible = { env, values -> gameScope(env) && values("linux.appGames") == "1" }),
    )

    private val LAUNCH_MODES = setOf("RealSteam", "AppSteam", "Goldberg", "Raw")
    private val RENDERERS = listOf("OpenGL", "Vulkan", "SurfaceFlinger")

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
        visible = { env, _ -> gameScope(env) },
        worlds = WINE_LINUX,
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
        visible = { env, values -> gameScope(env) && show(values) },
    )

    /** A builtin-or-native DLL row of the Win Components string (index 0 = Wine's, 1 = Microsoft's), as the classic tab offers it. */
    private fun dllRow(id: String, key: String, label: String, group: String) = wcRow(
        id, key, label, group, hint = null,
        help = "Builtin (Wine) uses Wine's own $label. Native (Windows) puts Microsoft's DLLs in the container and uses those instead. Change it only for a game that needs it.",
        options = listOf("Builtin (Wine)", "Native (Windows)"),
    )

    /**
     * A Codecs row. Codecs are two keys of the Win Components string, but media backends rather than
     * DLL overrides, so they get their own options (MediaDecoder).
     */
    private fun codecRow(id: String, key: String, label: String, hint: String) =
        wcRow(id, key, label, "Media codecs", hint = hint, help = null, options = MediaDecoder.optionsFor(key).toList())

    /**
     * One key of the Win Components string ("key=index,..."). A game keeps a components string of its
     * own only while it differs from the container's.
     */
    private fun wcRow(id: String, key: String, label: String, group: String, hint: String?, help: String?, options: List<String>) = SettingDef(
        id = id, category = CAT_COMPONENTS, group = group, level = ADVANCED,
        label = label, hint = hint, help = help, tech = "$WINCOMPONENTS › $key",
        control = { _, _ -> Control.Segmented(options.mapIndexed { i, name -> Opt("$i", name) }) },
        vm = VmBinding(
            get = { _, vm -> (vm.winComponents.firstOrNull { it.key == key }?.selectedIndex ?: 0).toString() },
            set = { _, vm, v ->
                val i = vm.winComponents.indexOfFirst { it.key == key }
                val index = v.toIntOrNull()
                if (i >= 0 && index != null) vm.winComponents[i] = vm.winComponents[i].copy(selectedIndex = index)
            },
        ),
        game = GameBinding(
            keys = listOf(WINCOMPONENTS),
            read = { e ->
                val own = wcIndex(e.get(WINCOMPONENTS), key)
                own?.takeIf { it != wcIndex(e.container.getWinComponents(), key) }?.toString()
            },
            write = { e, v -> v.toIntOrNull()?.let { e.put(WINCOMPONENTS, wcGameWith(e, key, it)) } },
            reset = { e ->
                if (e.get(WINCOMPONENTS) != null) e.put(WINCOMPONENTS, wcGameWith(e, key, wcIndex(e.container.getWinComponents(), key) ?: 0))
            },
        ),
        // Shown where the components list carries the key, as the classic Win Components tab shows it.
        visible = { env, _ ->
            env.vm?.winComponents?.any { it.key == key } == true ||
                wcIndex(env.shortcut?.getExtra(WINCOMPONENTS), key) != null
        },
    )
}
