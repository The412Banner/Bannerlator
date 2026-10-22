package com.winlator.star.ui.deck.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.preference.PreferenceManager
import com.winlator.star.box64.Box64Preset
import com.winlator.star.container.Container
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.UpdateManager
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.inputcontrols.SteamControllerBackend
import com.winlator.star.midi.MidiManager
import com.winlator.star.perf.PerfGpuTurbo
import com.winlator.star.perf.PerfRevertRegistry
import com.winlator.star.perf.PerfRootApplier
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import com.winlator.star.store.SteamFriendsStore
import com.winlator.star.store.SteamPrefs
import com.winlator.star.ui.components.GlobalControllerPrefs
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import com.winlator.star.ui.deck.settings.SettingLevel.EXPERT
import com.winlator.star.ui.deck.settings.SettingLevel.SIMPLE
import com.winlator.star.ui.screens.contents.ComponentLibrary
import com.winlator.star.ui.screens.contents.RemoteSourceRepository
import java.io.File
import java.util.Locale

// App-wide settings for the Deck Settings tab. Each one reads and writes the same preference the
// classic screen that owns it uses (Settings, Performance, Input Controls › Device, the Save Manager,
// Contents, Steam Friends), with the same side effects, and applies the moment it changes (there is
// nothing to save). What is not ported (storage folders, logs, backups, Steam region, Lossless.dll,
// the menu button) is one hand-off away.

internal object AppSettingsRegistry {

    val categories: List<SettingsCategory> = listOf(
        SettingsCategory("app.general", "General", "Start-up screen and updates.", Icons.Filled.Tune),
        SettingsCategory("app.appearance", "Appearance", "Theme, accent colour, size and orientation.", Icons.Filled.Palette),
        SettingsCategory("app.library", "Games & library", "Cover art and the default MIDI sound bank.", Icons.AutoMirrored.Filled.LibraryBooks),
        SettingsCategory("app.performance", "Performance", "Power defaults for every game and translator presets.", Icons.Filled.Speed),
        SettingsCategory("app.network", "Downloads & network", "Store download engines, speed and Steam connection.", Icons.Filled.CloudDownload),
        SettingsCategory("app.steam", "Steam friends", "Friends and chat, notifications and in-game presence.", Icons.Filled.Groups),
        SettingsCategory("app.saves", "Saves & cloud", "Automatic save backups and cloud sync for Steam, GOG and your own games.", Icons.Filled.Save),
        SettingsCategory("app.contents", "Contents", "Downloaded components: archives and the catalog list.", Icons.Filled.Inventory2),
        SettingsCategory("app.storage", "Storage", "Space used, app folders and the base system.", Icons.Filled.Storage),
        SettingsCategory("app.logs", "Logs & diagnostics", "What gets recorded, where it goes, how long it's kept.", Icons.Filled.Description),
        SettingsCategory("app.backup", "Backup & restore", "All app data, game saves and containers.", Icons.Filled.Archive),
        SettingsCategory("app.controllers", "Controllers", "Steam Controller, on-screen control defaults, and mouse and keyboard for Wine games.", Icons.Filled.Gamepad),
        SettingsCategory("app.about", "About & help", "Version, credits, help and community links.", Icons.Filled.Info),
        SettingsCategory("app.advanced", "Advanced", "Experimental switches and display server internals.", Icons.Filled.Science),
    )

    const val TARGET_CLASSIC = "classicSettings"
    const val TARGET_APPEARANCE = "appearance"
    const val TARGET_LOGS = "logs"
    const val TARGET_ABOUT = "about"
    const val TARGET_INPUT_CONTROLS = "inputControls"
    const val TARGET_DEFAULTS = "containerDefaults"
    const val TARGET_CONTENTS = "contents"

    val handoffs: Map<String, List<Handoff>> = mapOf(
        "app.general" to listOf(Handoff("Check for updates", "Version, release notes and Download & install (classic Settings).", TARGET_CLASSIC)),
        "app.appearance" to listOf(Handoff("Theme and colours", "Theme presets, custom accent colour, side menu, orientation, interface and text size.", TARGET_APPEARANCE)),
        "app.library" to listOf(Handoff("Installed sound banks and export folder", "Add .sf2 files and choose where shortcuts are exported (classic Settings).", TARGET_CLASSIC)),
        "app.performance" to listOf(
            Handoff("Root controls, heat safety and Lossless.dll", "CPU governor and frequency locks, free memory, the temperature watchdog (classic Settings › Performance), and importing Lossless.dll for frame generation.", TARGET_CLASSIC),
            Handoff("Edit translator presets", "Add, copy, delete, export or import Box64 and FEX presets (classic Settings).", TARGET_CLASSIC),
            Handoff("Defaults for new containers", "The All containers profile new containers start from. It comes before the default presets above.", TARGET_DEFAULTS),
        ),
        "app.network" to listOf(
            Handoff("Download engines and speed", "The native Steam engine, the Rust Epic / GOG / Amazon engines and store download speed (Log Manager).", TARGET_LOGS),
            Handoff("Steam connection region and network test", "Which Steam datacenter to use, testing regions, and the SteamLite network check (classic Settings).", TARGET_CLASSIC),
        ),
        "app.storage" to listOf(Handoff("Storage and folders", "App data folder, base system (ImageFS) and space used (classic Settings).", TARGET_CLASSIC)),
        "app.logs" to listOf(Handoff("Open the Log Manager", "Choose what is recorded (Wine, Box64/FEX, DXVK, SteamLite, Headless Steam, logcat), where logs go and how many are kept; browse and share them.", TARGET_LOGS)),
        "app.backup" to listOf(Handoff("Back up or restore all app data", "Containers, settings and prefix in one file (classic Settings).", TARGET_CLASSIC)),
        "app.controllers" to listOf(Handoff("Menu button, extra buttons and gyro calibration", "The pad button that opens the in-game side menu, Steam Controller L4/L5/R4/R5/QAM buttons, player slot pins and calibrating the gyroscope (Input Controls › Device).", TARGET_INPUT_CONTROLS)),
        "app.contents" to listOf(Handoff("Save location and repositories", "Where components are saved, adding and removing repositories, and \"Set as default…\" (Contents).", TARGET_CONTENTS)),
        "app.about" to listOf(Handoff("About Bannerlator", "Version, what's powering it and credits.", TARGET_ABOUT)),
        "app.advanced" to listOf(Handoff("Frame-gen training capture", "Contributing captures and the win-fg diagnostic log (classic Settings).", TARGET_CLASSIC)),
    )

    val defs: List<SettingDef> by lazy { buildDefs() }

    private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)
    private fun onOff(b: Boolean) = if (b) "1" else "0"

    private fun prefBool(key: String, default: Boolean) = AppBinding(
        get = { c -> onOff(prefs(c).getBoolean(key, default)) },
        set = { c, v -> prefs(c).edit().putBoolean(key, v == "1").apply() },
    )

    private fun app(
        id: String, category: String, group: String, label: String, level: SettingLevel,
        hint: String? = null, tech: String? = null,
        control: (SettingsEnv) -> Control, binding: AppBinding,
    ) = SettingDef(
        id = id, category = category, group = group, label = label, hint = hint, tech = tech, level = level,
        control = { env, _ -> control(env) }, app = binding,
    )

    private fun toggle(id: String, category: String, group: String, label: String, level: SettingLevel, key: String, default: Boolean, hint: String? = null) =
        app(id, category, group, label, level, hint = hint, tech = key, control = { Control.Toggle }, binding = prefBool(key, default))

    private fun buildDefs(): List<SettingDef> = listOf(
        app(
            "app.general.landing", "app.general", "Start-up", "Open the app on", SIMPLE,
            hint = "In the Deck interface, Games and Big Picture both open on Home.",
            tech = "default_landing_screen · enable_big_picture_mode",
            control = { Control.Segmented(listOf(Opt("games", "Games"), Opt("containers", "Containers"), Opt("bigpicture", "Big Picture"))) },
            binding = AppBinding(
                get = { c ->
                    val p = prefs(c)
                    if (p.getBoolean("enable_big_picture_mode", false)) "bigpicture"
                    else p.getString("default_landing_screen", "games") ?: "games"
                },
                set = { c, v ->
                    val e = prefs(c).edit()
                    if (v == "bigpicture") e.putBoolean("enable_big_picture_mode", true)
                    else e.putBoolean("enable_big_picture_mode", false).putString("default_landing_screen", v)
                    e.apply()
                },
            ),
        ),
        app(
            "app.general.updateNotify", "app.general", "Updates", "Tell me about new versions", SIMPLE,
            hint = "Shows the update notice when a new version is out.", tech = "Notify me about updates",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(UpdateManager.isNotifyEnabled(c)) }, { c, v -> UpdateManager.setNotifyEnabled(c, v == "1") }),
        ),
        app(
            "app.general.updatePrerelease", "app.general", "Updates", "Include beta builds", ADVANCED,
            hint = "Pre-releases may be less stable.", tech = "Include pre-releases",
            control = { Control.Toggle },
            // A channel switch re-checks at once, as classic Settings does: an unforced check would answer from the 6 h cache.
            binding = AppBinding(
                get = { c -> onOff(UpdateManager.isIncludePrereleases(c)) },
                set = { c, v ->
                    UpdateManager.setIncludePrereleases(c, v == "1")
                    UpdateManager.check(c, force = true) { }
                },
            ),
        ),

        app(
            "app.library.steamGridKey", "app.library", "Cover art", "SteamGridDB API key", ADVANCED,
            hint = "Optional. Fetches cover art for games that aren't from a store. Leave empty to turn it off.",
            control = { Control.TextField("API key") },
            binding = AppBinding(
                get = { c ->
                    val p = prefs(c)
                    if (p.getBoolean("enable_custom_api_key", false)) p.getString("custom_api_key", "") ?: "" else ""
                },
                set = { c, v ->
                    val key = v.trim()
                    val e = prefs(c).edit()
                    if (key.isEmpty()) e.putBoolean("enable_custom_api_key", false).remove("custom_api_key")
                    else e.putBoolean("enable_custom_api_key", true).putString("custom_api_key", key)
                    e.apply()
                },
            ),
        ),
        app(
            "app.library.midiDefault", "app.library", "Sound (MIDI)", "Default MIDI sound bank", ADVANCED,
            hint = "Used by new containers. Each container can pick its own under Audio.", tech = MidiManager.PREF_DEFAULT_SOUND_FONT,
            control = { env ->
                val files = MidiManager.getSoundFontDir(env.context).listFiles()
                    ?.map { it.name }?.filter { it.endsWith(".sf2") && it != MidiManager.DEFAULT_SF2_FILE }?.sorted() ?: emptyList()
                Control.Select(listOf(Opt("", "Off"), Opt(MidiManager.DEFAULT_SF2_FILE, "Built-in General MIDI bank")) + files.map { Opt(it, it) })
            },
            binding = AppBinding(
                // A default that points at a removed SoundFont falls back to Off, as classic Settings shows it.
                get = { c ->
                    val name = prefs(c).getString(MidiManager.PREF_DEFAULT_SOUND_FONT, "") ?: ""
                    if (name.isEmpty() || name == MidiManager.DEFAULT_SF2_FILE || File(MidiManager.getSoundFontDir(c), name).isFile) name else ""
                },
                set = { c, v -> prefs(c).edit().putString(MidiManager.PREF_DEFAULT_SOUND_FONT, v).apply() },
            ),
        ),

        perf("app.performance.sustained", "Sustained performance mode", "Keeps clocks steady for long sessions instead of short bursts.",
            { PerformanceSettings.sustainedPerfMode.value }, { PerformanceSettings.setSustainedPerfMode(it) }),
        perf("app.performance.threadBoost", "Boost game thread priority", null,
            { PerformanceSettings.perfPriorityBoost.value }, { PerformanceSettings.setPerfPriorityBoost(it) }),
        perf("app.performance.bigCores", "Prefer big CPU cores", null,
            { PerformanceSettings.preferBigCores.value }, { PerformanceSettings.setPreferBigCores(it) }),
        app(
            "app.performance.box64Preset", "app.performance", "Translator presets", "Default Box64 preset", ADVANCED,
            hint = "Used by new containers.", tech = "box64_preset",
            control = { env -> Control.Select(env.box64Presets.map { Opt(it.id, it.name) }) },
            binding = AppBinding(
                get = { c -> prefs(c).getString("box64_preset", Box64Preset.COMPATIBILITY) ?: Box64Preset.COMPATIBILITY },
                set = { c, v -> prefs(c).edit().putString("box64_preset", v).apply() },
            ),
        ),
        app(
            "app.performance.fexPreset", "app.performance", "Translator presets", "Default FEX preset", ADVANCED,
            hint = "Used by new containers.", tech = "fexcore_preset",
            control = { env -> Control.Select(env.fexPresets.map { Opt(it.id, it.name) }) },
            binding = AppBinding(
                get = { c -> prefs(c).getString("fexcore_preset", FEXCorePreset.COMPATIBILITY) ?: FEXCorePreset.COMPATIBILITY },
                set = { c, v -> prefs(c).edit().putString("fexcore_preset", v).apply() },
            ),
        ),

        app(
            "app.controllers.cursorSpeed", "app.controllers", "Mouse & keyboard (Wine games)", "Cursor speed", ADVANCED, tech = "cursor_speed",
            control = { Control.Slider(0.1f, 2.0f, 0.05f, { "${(it * 100).toInt()}%" }, { String.format(Locale.ENGLISH, "%.2f", it) }) },
            binding = AppBinding(
                get = { c -> String.format(Locale.ENGLISH, "%.2f", prefs(c).getFloat("cursor_speed", 1.0f)) },
                set = { c, v -> v.toFloatOrNull()?.let { prefs(c).edit().putFloat("cursor_speed", it).apply() } },
            ),
        ),
        toggle("app.controllers.trueMouse", "app.controllers", "Mouse & keyboard (Wine games)", "True mouse control", ADVANCED, "cursor_lock", false,
            hint = "Locks the pointer to the game. Press Volume Down to release."),
        toggle("app.controllers.disableXinput", "app.controllers", "Mouse & keyboard (Wine games)", "Exclusive mouse & keyboard", ADVANCED, "xinput_toggle", false,
            hint = "Turns off XInput so games see only the real mouse and keyboard."),

        toggle("app.advanced.fileProvider", "app.advanced", "Experimental", "Share files with other apps (File Provider)", ADVANCED, "enable_file_provider", true,
            hint = "Takes effect after the app restarts."),
        toggle("app.advanced.androidBrowser", "app.advanced", "Experimental", "Open links in the Android browser", ADVANCED, "open_with_android_browser", false),
        toggle("app.advanced.clipboard", "app.advanced", "Experimental", "Share the Android clipboard with games", ADVANCED, "share_android_clipboard", false),
        app(
            "app.advanced.contentsUrl", "app.advanced", "Experimental", "Components catalog URL", EXPERT,
            hint = "Where the Components screen looks for downloads.", tech = "downloadable_contents_url",
            control = { Control.TextField(ContentsManager.REMOTE_PROFILES) { if (it.isBlank() || it.trim().startsWith("http")) null else "Use an http(s) address." } },
            binding = AppBinding(
                get = { c -> prefs(c).getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES) ?: ContentsManager.REMOTE_PROFILES },
                set = { c, v -> prefs(c).edit().putString("downloadable_contents_url", v.trim().ifEmpty { ContentsManager.REMOTE_PROFILES }).apply() },
            ),
        ),
        toggle("app.advanced.dri3", "app.advanced", "Display server (X11)", "Use DRI3", ADVANCED, "use_dri3", true),
        toggle("app.advanced.xr", "app.advanced", "Display server (X11)", "Allow XR / VR headsets", ADVANCED, "use_xr", true),

        // The GPU pin is a non-root default on Adreno (KGSL turbo); elsewhere it needs root, as in classic Settings › Performance.
        SettingDef(
            id = "app.performance.gpuClockLock", category = "app.performance", group = "Power (defaults for every game)",
            label = "Lock GPU to max clock", level = ADVANCED, tech = "global_${PerfRootApplier.KEY_GPU_CLOCK_LOCK}",
            hint = "Holds the GPU at its top clock while a game runs. Hotter and hungrier; games can override it.",
            control = { _, _ -> Control.Toggle },
            app = AppBinding(
                get = { _ -> onOff(PerformanceSettings.rootDefaultValue(PerfRootApplier.KEY_GPU_CLOCK_LOCK)) },
                set = { _, v ->
                    PerformanceSettings.setRootDefault(PerfRootApplier.KEY_GPU_CLOCK_LOCK, v == "1")
                    PerfRootApplier.apply(PerfRootApplier.KEY_GPU_CLOCK_LOCK, v == "1")
                },
            ),
            disabled = { _, _ ->
                val key = PerfRootApplier.KEY_GPU_CLOCK_LOCK
                when {
                    PerfRootApplier.isHarnessGated(key) && !PerfRevertRegistry.harnessProven.value -> "Waiting for the safety check to pass on this device."
                    RootManager.isGranted || PerfGpuTurbo.isSupported -> null
                    else -> "Needs an Adreno (Qualcomm) GPU, or root on other GPUs."
                }
            },
        ),

        // Steam friends (the same switches as the Friends screen's settings and classic Settings › Steam).
        app(
            "app.steam.social", "app.steam", "Friends & chat", "Steam friends & chat", SIMPLE,
            hint = "Your friends list, presence and chat. Off: no online status is shared.", tech = "steam_social_enabled",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(SteamPrefs.isSocialEnabled(c)) }, { c, v -> SteamFriendsStore.setSocialEnabled(c, v == "1") }),
        ),
        app(
            "app.steam.chatNotifications", "app.steam", "Friends & chat", "Chat notifications", SIMPLE,
            hint = "A notification when a friend messages you and their chat isn't open.", tech = "steam_chat_notifications",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(SteamPrefs.isChatNotificationsEnabled(c)) }, { c, v -> SteamPrefs.setChatNotificationsEnabled(c, v == "1") }),
        ),
        SettingDef(
            id = "app.steam.chatSound", category = "app.steam", group = "Friends & chat", label = "Notification sound", level = ADVANCED,
            hint = "A sound and a heads-up for new messages.", tech = "steam_chat_sound",
            control = { _, _ -> Control.Toggle },
            app = AppBinding({ c -> onOff(SteamPrefs.isChatSoundEnabled(c)) }, { c, v -> SteamPrefs.setChatSoundEnabled(c, v == "1") }),
            disabled = { _, values ->
                if (values("app.steam.social") == "1" && values("app.steam.chatNotifications") == "1") null else "Needs friends & chat and chat notifications on."
            },
        ),
        app(
            "app.steam.offlinePresence", "app.steam", "Presence", "Show me as in-game for offline launches", ADVANCED,
            hint = "When a Steam game starts with Goldberg or Raw .exe (Wine games), tell Steam you're playing it so friends see it and playtime counts. SteamLite launches always show.",
            tech = "steam_offline_presence",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(SteamPrefs.isOfflinePresenceEnabled(c)) }, { c, v -> SteamPrefs.setOfflinePresenceEnabled(c, v == "1") }),
        ),

        // Save Manager › Settings: turning a backup off asks first, as there.
        saveToggle("app.saves.steamCollect", "Steam games", "Auto-collect on exit", "Snapshot Steam-library saves to your local Library when a game exits.",
            "auto_collect_steam_on_exit",
            "Automatic save backup on exit will be OFF for your Steam library games. Their saves won't be captured when a game closes — you'll need to back them up yourself via a container's backup option or the Save Manager. Continue?"),
        saveToggle("app.saves.steamUpload", "Steam games", "Auto-upload to Steam Cloud on exit", "Only after you accept the third-party cloud disclaimer; never deletes anything from the cloud.",
            "auto_upload_steam_on_exit",
            "Automatic cloud upload on exit will be OFF for your Steam library games. Their saves won't be pushed to Steam Cloud when a game closes — you'll need to upload them yourself from the game's detail page Cloud Saves section. Continue?"),
        saveToggle("app.saves.steamDownload", "Steam games", "Auto-download from Steam Cloud on launch", "Newest wins: never overwrites a newer local save.",
            "auto_download_steam_on_launch",
            "Automatic cloud download on launch will be OFF for your Steam library games. The latest cloud saves won't be pulled in before a game starts — you'll need to download them yourself from the game's detail page Cloud Saves section. Continue?"),
        // Off by default (the other save switches default on), so it can't go through saveToggle.
        SettingDef(
            id = "app.saves.goldbergCloud", category = "app.saves", group = "Steam games",
            label = "Sync with Steam Cloud during Goldberg launches", level = ADVANCED, tech = "goldberg_cloud_sync",
            hint = "Off: Goldberg is fully offline, with no cloud pull, push or achievement upload. On: those run for Goldberg launches too.",
            control = { _, _ -> Control.Toggle },
            app = AppBinding(
                get = { c -> onOff(saveManagerPrefs(c).getBoolean("goldberg_cloud_sync", false)) },
                set = { c, v -> saveManagerPrefs(c).edit().putBoolean("goldberg_cloud_sync", v == "1").apply() },
            ),
        ),
        saveToggle("app.saves.gogUpload","GOG games", "Auto-upload to GOG cloud on exit", "Newest wins: never overwrites a newer cloud save.",
            "auto_upload_gog_on_exit",
            "Automatic cloud upload on exit will be OFF for your GOG library games. Their saves won't be pushed to GOG cloud when a game closes — you'll need to upload them yourself from the GOG Save Manager tab or the game's detail page. Continue?"),
        saveToggle("app.saves.gogDownload", "GOG games", "Auto-download from GOG cloud on launch", "Newest wins: never overwrites a newer local save.",
            "auto_download_gog_on_launch",
            "Automatic cloud download on launch will be OFF for your GOG library games. The latest cloud saves won't be pulled in before a game starts — you'll need to download them yourself from the GOG Save Manager tab or the game's detail page. Continue?"),
        saveToggle("app.saves.customBackup", "Your own games", "Auto-back up on exit", "Snapshot custom-import saves to the local vault when a game exits.",
            "auto_backup_custom_on_exit",
            "Automatic save backup on exit will be OFF for your custom-imported games. Their saves won't be captured when a game closes — you'll need to back them up yourself via a container's backup option or the game's ⋮ menu → 'Back up saves'. Continue?"),
        // Writes to the real Steam account, so turning it ON is the guarded step.
        SettingDef(
            id = "app.saves.achievementSync", category = "app.saves", group = "Steam achievements",
            label = "Sync unlocks to your Steam profile", level = ADVANCED, tech = "achievement_syncback_enabled",
            hint = "Achievements you unlock in-game are written back to your real Steam account. Off: unlocks stay on this device.",
            control = { _, _ -> Control.Toggle },
            app = AppBinding({ c -> onOff(SteamPrefs.isAchievementSyncBackEnabled(c)) }, { c, v -> SteamPrefs.setAchievementSyncBackEnabled(c, v == "1") }),
            confirm = { v ->
                if (v == "1") Confirm(
                    "Sync achievements to Steam?",
                    "Turning this ON writes achievements you unlock in-game back to your REAL Steam account and profile. This is a third-party sync — it can't be undone on Steam and may not match what official Steam would record. Leave it off to keep unlocks local to this device. Turn on anyway?",
                    "Turn on",
                ) else null
            },
        ),

        // Contents › Contents settings
        app(
            "app.contents.keepRaw", "app.contents", "Downloads", "Keep raw archive by default", ADVANCED,
            hint = "Save every install's download to My Files.", tech = "keep_raw",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(ComponentLibrary(c).keepRaw()) }, { c, v -> ComponentLibrary(c).setKeepRaw(v == "1") }),
        ),
        app(
            "app.contents.showOfficial", "app.contents", "Repositories", "Show Official catalog in repositories", ADVANCED,
            hint = "Off hides it in Contents; the container and game sheets keep it.", tech = "show_official",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(RemoteSourceRepository(c).showOfficial()) }, { c, v -> RemoteSourceRepository(c).setShowOfficial(v == "1") }),
        ),

        // Input Controls › Device and › Assign
        app(
            "app.controllers.steamController", "app.controllers", "Steam Controller", "Steam Controller support", SIMPLE,
            hint = "Reads a Steam Controller directly (its trackpads and extra buttons). Off: the normal input path is unchanged.", tech = "steam_controller_sdl_enabled",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(GlobalControllerPrefs.isSteamControllerEnabled(c)) }, { c, v -> GlobalControllerPrefs.setSteamControllerEnabled(c, v == "1") }),
        ),
        SettingDef(
            id = "app.controllers.trackpadMouse", category = "app.controllers", group = "Steam Controller", label = "Trackpad mouse", level = ADVANCED,
            hint = "Which trackpad moves the mouse. Clicking it is a left click.", tech = "steam_controller_trackpad_mode",
            control = { _, _ ->
                Control.Segmented(listOf(
                    Opt("${SteamControllerBackend.TRACKPAD_MOUSE_RIGHT}", "Right"),
                    Opt("${SteamControllerBackend.TRACKPAD_MOUSE_LEFT}", "Left"),
                    Opt("${SteamControllerBackend.TRACKPAD_MOUSE_BOTH}", "Both"),
                    Opt("${SteamControllerBackend.TRACKPAD_MOUSE_OFF}", "Off"),
                ))
            },
            app = AppBinding(
                get = { c -> GlobalControllerPrefs.getSteamTrackpadMouseMode(c).toString() },
                set = { c, v -> v.toIntOrNull()?.let { GlobalControllerPrefs.setSteamTrackpadMouseMode(c, it) } },
            ),
            visible = { _, values -> values("app.controllers.steamController") == "1" },
        ),
        app(
            "app.controllers.onScreenPriority", "app.controllers", "New containers start with", "On-screen controls vs. a real pad", ADVANCED,
            hint = "Copied into each new container; existing containers keep their own.", tech = "global_on_screen_controller_mode",
            control = {
                Control.Segmented(listOf(
                    Opt("${Container.ON_SCREEN_MODE_KEEP}", "On-screen stays Player 1"),
                    Opt("${Container.ON_SCREEN_MODE_YIELD}", "Pad becomes Player 1"),
                    Opt("${Container.ON_SCREEN_MODE_SHARE}", "Both control Player 1"),
                ))
            },
            binding = AppBinding(
                get = { c -> GlobalControllerPrefs.getOnScreenMode(c).toString() },
                set = { c, v -> v.toIntOrNull()?.let { GlobalControllerPrefs.setOnScreenMode(c, it) } },
            ),
        ),
        app(
            "app.controllers.autoHide", "app.controllers", "New containers start with", "Hide on-screen controls when a controller connects", ADVANCED,
            hint = "Copied into each new container; existing containers keep their own.", tech = "global_auto_hide_controls_on_pad",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(GlobalControllerPrefs.getAutoHideControlsOnPad(c)) }, { c, v -> GlobalControllerPrefs.setAutoHideControlsOnPad(c, v == "1") }),
        ),
    )

    /** A Save Manager auto-sync switch (default on). Turning it off asks first with the Save Manager's own warning. */
    private fun saveToggle(id: String, group: String, label: String, hint: String, key: String, offWarning: String) = SettingDef(
        id = id, category = "app.saves", group = group, label = label, hint = hint, tech = key, level = ADVANCED,
        control = { _, _ -> Control.Toggle },
        app = AppBinding(
            get = { c -> onOff(saveManagerPrefs(c).getBoolean(key, true)) },
            set = { c, v -> saveManagerPrefs(c).edit().putBoolean(key, v == "1").apply() },
        ),
        confirm = { v -> if (v == "0") Confirm("Turn off auto-backup?", offWarning, "Continue") else null },
    )

    private fun saveManagerPrefs(c: Context) = c.getSharedPreferences("save_manager_prefs", Context.MODE_PRIVATE)

    /** An app-wide power default. Games can override it in their own editor (Performance › Power). */
    private fun perf(id: String, label: String, hint: String?, get: () -> Boolean, set: (Boolean) -> Unit) = app(
        id, "app.performance", "Power (defaults for every game)", label, ADVANCED, hint = hint,
        control = { Control.Toggle },
        binding = AppBinding(get = { _ -> onOff(get()) }, set = { _, v -> set(v == "1") }),
    )
}
