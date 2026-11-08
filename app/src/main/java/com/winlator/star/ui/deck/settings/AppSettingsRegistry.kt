package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.T
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
        SettingsCategory("app.general", T("General"), T("Start-up screen and updates."), Icons.Filled.Tune),
        SettingsCategory("app.appearance", T("Appearance"), T("Theme, accent colour, size and orientation."), Icons.Filled.Palette),
        SettingsCategory("app.library", T("Games & library"), T("Cover art and the default MIDI sound bank."), Icons.AutoMirrored.Filled.LibraryBooks),
        SettingsCategory("app.performance", T("Performance"), T("Power defaults for every game and translator presets."), Icons.Filled.Speed),
        SettingsCategory("app.network", T("Downloads & network"), T("Store download engines, speed and Steam connection."), Icons.Filled.CloudDownload),
        SettingsCategory("app.steam", T("Steam friends"), T("Friends and chat, notifications and in-game presence."), Icons.Filled.Groups),
        SettingsCategory("app.saves", T("Saves & cloud"), T("Automatic save backups and cloud sync for Steam, GOG and your own games."), Icons.Filled.Save),
        SettingsCategory("app.contents", T("Contents"), T("Downloaded components: archives and the catalog list."), Icons.Filled.Inventory2),
        SettingsCategory("app.storage", T("Storage"), T("Space used, app folders and the base system."), Icons.Filled.Storage),
        SettingsCategory("app.logs", T("Logs & diagnostics"), T("What gets recorded, where it goes, how long it's kept."), Icons.Filled.Description),
        SettingsCategory("app.backup", T("Backup & restore"), T("All app data, game saves and containers."), Icons.Filled.Archive),
        SettingsCategory("app.controllers", T("Controllers"), T("Steam Controller, on-screen control defaults, and mouse and keyboard for Wine games."), Icons.Filled.Gamepad),
        SettingsCategory("app.about", T("About & help"), T("Version, credits, help and community links."), Icons.Filled.Info),
        SettingsCategory("app.advanced", T("Advanced"), T("Experimental switches and display server internals."), Icons.Filled.Science),
    )

    const val TARGET_CLASSIC = "classicSettings"
    const val TARGET_APPEARANCE = "appearance"
    const val TARGET_LOGS = "logs"
    const val TARGET_ABOUT = "about"
    const val TARGET_INPUT_CONTROLS = "inputControls"
    const val TARGET_DEFAULTS = "containerDefaults"
    const val TARGET_CONTENTS = "contents"

    val handoffs: Map<String, List<Handoff>> = mapOf(
        "app.general" to listOf(Handoff(T("Check for updates"), T("Version, release notes and Download & install (classic Settings)."), TARGET_CLASSIC)),
        "app.appearance" to listOf(Handoff(T("Theme and colours"), T("Theme presets, custom accent colour, side menu, orientation, interface and text size."), TARGET_APPEARANCE)),
        "app.library" to listOf(Handoff(T("Installed sound banks and export folder"), T("Add .sf2 files and choose where shortcuts are exported (classic Settings)."), TARGET_CLASSIC)),
        "app.performance" to listOf(
            Handoff(T("Root controls, heat safety and Lossless.dll"), T("CPU governor and frequency locks, free memory, the temperature watchdog (classic Settings › Performance), and importing Lossless.dll for frame generation."), TARGET_CLASSIC),
            Handoff(T("Edit translator presets"), T("Add, copy, delete, export or import Box64 and FEX presets (classic Settings)."), TARGET_CLASSIC),
            Handoff(T("Defaults for new containers"), T("The All containers profile new containers start from. It comes before the default presets above."), TARGET_DEFAULTS),
        ),
        "app.network" to listOf(
            Handoff(T("Download engines and speed"), T("The native Steam engine, the Rust Epic / GOG / Amazon engines and store download speed (Log Manager)."), TARGET_LOGS),
            Handoff(T("Steam connection region and network test"), T("Which Steam datacenter to use, testing regions, and the SteamLite network check (classic Settings)."), TARGET_CLASSIC),
        ),
        "app.storage" to listOf(Handoff(T("Storage and folders"), T("App data folder, base system (ImageFS) and space used (classic Settings)."), TARGET_CLASSIC)),
        "app.logs" to listOf(Handoff(T("Open the Log Manager"), T("Choose what is recorded (Wine, Box64/FEX, DXVK, SteamLite, Headless Steam, logcat), where logs go and how many are kept; browse and share them."), TARGET_LOGS)),
        "app.backup" to listOf(Handoff(T("Back up or restore all app data"), T("Containers, settings and prefix in one file (classic Settings)."), TARGET_CLASSIC)),
        "app.controllers" to listOf(Handoff(T("Menu button, extra buttons and gyro calibration"), T("The pad button that opens the in-game side menu, Steam Controller L4/L5/R4/R5/QAM buttons, player slot pins and calibrating the gyroscope (Input Controls › Device)."), TARGET_INPUT_CONTROLS)),
        "app.contents" to listOf(Handoff(T("Save location and repositories"), T("Where components are saved, adding and removing repositories, and \"Set as default…\" (Contents)."), TARGET_CONTENTS)),
        "app.about" to listOf(Handoff(T("About Bannerlator"), T("Version, what's powering it and credits."), TARGET_ABOUT)),
        "app.advanced" to listOf(Handoff(T("Frame-gen training capture"), T("Contributing captures and the win-fg diagnostic log (classic Settings)."), TARGET_CLASSIC)),
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
            "app.general.landing", "app.general", T("Start-up"), T("Open the app on"), SIMPLE,
            hint = T("In the Deck interface, Games and Big Picture both open on Home."),
            tech = T("default_landing_screen · enable_big_picture_mode"),
            control = { Control.Segmented(listOf(Opt("games", T("Games")), Opt("containers", T("Containers")), Opt("bigpicture", T("Big Picture")))) },
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
            "app.general.updateNotify", "app.general", T("Updates"), T("Tell me about new versions"), SIMPLE,
            hint = T("Shows the update notice when a new version is out."), tech = T("Notify me about updates"),
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(UpdateManager.isNotifyEnabled(c)) }, { c, v -> UpdateManager.setNotifyEnabled(c, v == "1") }),
        ),
        app(
            "app.general.updatePrerelease", "app.general", T("Updates"), T("Include beta builds"), ADVANCED,
            hint = T("Pre-releases may be less stable."), tech = T("Include pre-releases"),
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
            "app.library.steamGridKey", "app.library", T("Cover art"), T("SteamGridDB API key"), ADVANCED,
            hint = T("Optional. Fetches cover art for games that aren't from a store. Leave empty to turn it off."),
            control = { Control.TextField(T("API key")) },
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
            "app.library.midiDefault", "app.library", T("Sound (MIDI)"), T("Default MIDI sound bank"), ADVANCED,
            hint = T("Used by new containers. Each container can pick its own under Audio."), tech = MidiManager.PREF_DEFAULT_SOUND_FONT,
            control = { env ->
                val files = MidiManager.getSoundFontDir(env.context).listFiles()
                    ?.map { it.name }?.filter { it.endsWith(".sf2") && it != MidiManager.DEFAULT_SF2_FILE }?.sorted() ?: emptyList()
                Control.Select(listOf(Opt("", T("Off")), Opt(MidiManager.DEFAULT_SF2_FILE, T("Built-in General MIDI bank"))) + files.map { Opt(it, it) })
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

        perf("app.performance.sustained", T("Sustained performance mode"), T("Keeps clocks steady for long sessions instead of short bursts."),
            { PerformanceSettings.sustainedPerfMode.value }, { PerformanceSettings.setSustainedPerfMode(it) }),
        perf("app.performance.threadBoost", T("Boost game thread priority"), null,
            { PerformanceSettings.perfPriorityBoost.value }, { PerformanceSettings.setPerfPriorityBoost(it) }),
        perf("app.performance.bigCores", T("Prefer big CPU cores"), null,
            { PerformanceSettings.preferBigCores.value }, { PerformanceSettings.setPreferBigCores(it) }),
        app(
            "app.performance.box64Preset", "app.performance", T("Translator presets"), T("Default Box64 preset"), ADVANCED,
            hint = T("Used by new containers."), tech = "box64_preset",
            control = { env -> Control.Select(env.box64Presets.map { Opt(it.id, it.name) }) },
            binding = AppBinding(
                get = { c -> prefs(c).getString("box64_preset", Box64Preset.COMPATIBILITY) ?: Box64Preset.COMPATIBILITY },
                set = { c, v -> prefs(c).edit().putString("box64_preset", v).apply() },
            ),
        ),
        app(
            "app.performance.fexPreset", "app.performance", T("Translator presets"), T("Default FEX preset"), ADVANCED,
            hint = T("Used by new containers."), tech = "fexcore_preset",
            control = { env -> Control.Select(env.fexPresets.map { Opt(it.id, it.name) }) },
            binding = AppBinding(
                get = { c -> prefs(c).getString("fexcore_preset", FEXCorePreset.COMPATIBILITY) ?: FEXCorePreset.COMPATIBILITY },
                set = { c, v -> prefs(c).edit().putString("fexcore_preset", v).apply() },
            ),
        ),

        app(
            "app.controllers.cursorSpeed", "app.controllers", T("Mouse & keyboard (Wine games)"), T("Cursor speed"), ADVANCED, tech = "cursor_speed",
            control = { Control.Slider(0.1f, 2.0f, 0.05f, { "${(it * 100).toInt()}%" }, { String.format(Locale.ENGLISH, "%.2f", it) }) },
            binding = AppBinding(
                get = { c -> String.format(Locale.ENGLISH, "%.2f", prefs(c).getFloat("cursor_speed", 1.0f)) },
                set = { c, v -> v.toFloatOrNull()?.let { prefs(c).edit().putFloat("cursor_speed", it).apply() } },
            ),
        ),
        toggle("app.controllers.trueMouse", "app.controllers", T("Mouse & keyboard (Wine games)"), T("True mouse control"), ADVANCED, "cursor_lock", false,
            hint = T("Locks the pointer to the game. Press Volume Down to release.")),
        toggle("app.controllers.disableXinput", "app.controllers", T("Mouse & keyboard (Wine games)"), T("Exclusive mouse & keyboard"), ADVANCED, "xinput_toggle", false,
            hint = T("Turns off XInput so games see only the real mouse and keyboard.")),

        toggle("app.advanced.fileProvider", "app.advanced", T("Experimental"), T("Share files with other apps (File Provider)"), ADVANCED, "enable_file_provider", true,
            hint = T("Takes effect after the app restarts.")),
        toggle("app.advanced.androidBrowser", "app.advanced", T("Experimental"), T("Open links in the Android browser"), ADVANCED, "open_with_android_browser", false),
        toggle("app.advanced.clipboard", "app.advanced", T("Experimental"), T("Share the Android clipboard with games"), ADVANCED, "share_android_clipboard", false),
        app(
            "app.advanced.contentsUrl", "app.advanced", T("Experimental"), T("Components catalog URL"), EXPERT,
            hint = T("Where the Components screen looks for downloads."), tech = "downloadable_contents_url",
            control = { Control.TextField(ContentsManager.REMOTE_PROFILES) { if (it.isBlank() || it.trim().startsWith("http")) null else T("Use an http(s) address.") } },
            binding = AppBinding(
                get = { c -> prefs(c).getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES) ?: ContentsManager.REMOTE_PROFILES },
                set = { c, v -> prefs(c).edit().putString("downloadable_contents_url", v.trim().ifEmpty { ContentsManager.REMOTE_PROFILES }).apply() },
            ),
        ),
        toggle("app.advanced.dri3", "app.advanced", T("Display server (X11)"), T("Use DRI3"), ADVANCED, "use_dri3", true),
        toggle("app.advanced.xr", "app.advanced", T("Display server (X11)"), T("Allow XR / VR headsets"), ADVANCED, "use_xr", true),

        // The FPS saver (XServerDisplayActivity.saveSessionFps): counts frames with the overlay off and keeps each session's FPS.
        toggle("app.performance.saveSessionFps", "app.performance", T("FPS history"), T("Save FPS of each session"), SIMPLE, "save_session_fps", true),
        // The GPU pin is a non-root default on Adreno (KGSL turbo); elsewhere it needs root, as in classic Settings › Performance.
        SettingDef(
            id = "app.performance.gpuClockLock", category = "app.performance", group = T("Power (defaults for every game)"),
            label = T("Lock GPU to max clock"), level = ADVANCED, tech = "global_${PerfRootApplier.KEY_GPU_CLOCK_LOCK}",
            hint = T("Holds the GPU at its top clock while a game runs. Hotter and hungrier; games can override it."),
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
                    PerfRootApplier.isHarnessGated(key) && !PerfRevertRegistry.harnessProven.value -> T("Waiting for the safety check to pass on this device.")
                    RootManager.isGranted || PerfGpuTurbo.isSupported -> null
                    else -> T("Needs an Adreno (Qualcomm) GPU, or root on other GPUs.")
                }
            },
        ),

        // Steam friends (the same switches as the Friends screen's settings and classic Settings › Steam).
        app(
            "app.steam.social", "app.steam", T("Friends & chat"), T("Steam friends & chat"), SIMPLE,
            hint = T("Your friends list, presence and chat. Off: no online status is shared."), tech = "steam_social_enabled",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(SteamPrefs.isSocialEnabled(c)) }, { c, v -> SteamFriendsStore.setSocialEnabled(c, v == "1") }),
        ),
        app(
            "app.steam.chatNotifications", "app.steam", T("Friends & chat"), T("Chat notifications"), SIMPLE,
            hint = T("A notification when a friend messages you and their chat isn't open."), tech = "steam_chat_notifications",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(SteamPrefs.isChatNotificationsEnabled(c)) }, { c, v -> SteamPrefs.setChatNotificationsEnabled(c, v == "1") }),
        ),
        SettingDef(
            id = "app.steam.chatSound", category = "app.steam", group = T("Friends & chat"), label = T("Notification sound"), level = ADVANCED,
            hint = T("A sound and a heads-up for new messages."), tech = "steam_chat_sound",
            control = { _, _ -> Control.Toggle },
            app = AppBinding({ c -> onOff(SteamPrefs.isChatSoundEnabled(c)) }, { c, v -> SteamPrefs.setChatSoundEnabled(c, v == "1") }),
            disabled = { _, values ->
                if (values("app.steam.social") == "1" && values("app.steam.chatNotifications") == "1") null else T("Needs friends & chat and chat notifications on.")
            },
        ),
        app(
            "app.steam.offlinePresence", "app.steam", T("Presence"), T("Show me as in-game for offline launches"), ADVANCED,
            hint = T("When a Steam game starts with Goldberg or Raw .exe (Wine games), tell Steam you're playing it so friends see it and playtime counts. SteamLite launches always show."),
            tech = "steam_offline_presence",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(SteamPrefs.isOfflinePresenceEnabled(c)) }, { c, v -> SteamPrefs.setOfflinePresenceEnabled(c, v == "1") }),
        ),

        // Save Manager › Settings: turning a backup off asks first, as there.
        saveToggle("app.saves.steamCollect", T("Steam games"), T("Auto-collect on exit"), T("Snapshot Steam-library saves to your local Library when a game exits."),
            "auto_collect_steam_on_exit",
            T("Automatic save backup on exit will be OFF for your Steam library games. Their saves won't be captured when a game closes — you'll need to back them up yourself via a container's backup option or the Save Manager. Continue?")),
        saveToggle("app.saves.steamUpload", T("Steam games"), T("Auto-upload to Steam Cloud on exit"), T("Only after you accept the third-party cloud disclaimer; never deletes anything from the cloud."),
            "auto_upload_steam_on_exit",
            T("Automatic cloud upload on exit will be OFF for your Steam library games. Their saves won't be pushed to Steam Cloud when a game closes — you'll need to upload them yourself from the game's detail page Cloud Saves section. Continue?")),
        saveToggle("app.saves.steamDownload", T("Steam games"), T("Auto-download from Steam Cloud on launch"), T("Newest wins: never overwrites a newer local save."),
            "auto_download_steam_on_launch",
            T("Automatic cloud download on launch will be OFF for your Steam library games. The latest cloud saves won't be pulled in before a game starts — you'll need to download them yourself from the game's detail page Cloud Saves section. Continue?")),
        // Not a saveToggle: that asks "Turn off auto-backup?", and this switch leaves local backups alone.
        SettingDef(
            id = "app.saves.goldbergCloud", category = "app.saves", group = T("Steam games"),
            label = T("Sync with Steam Cloud during Goldberg launches"), level = ADVANCED, tech = "goldberg_cloud_sync",
            hint = T("Off: Goldberg is fully offline, with no cloud pull, push or achievement upload. On: those run for Goldberg launches too."),
            control = { _, _ -> Control.Toggle },
            app = AppBinding(
                get = { c -> onOff(saveManagerPrefs(c).getBoolean("goldberg_cloud_sync", true)) },
                set = { c, v -> saveManagerPrefs(c).edit().putBoolean("goldberg_cloud_sync", v == "1").apply() },
            ),
        ),
        saveToggle("app.saves.gogUpload",T("GOG games"), T("Auto-upload to GOG cloud on exit"), T("Newest wins: never overwrites a newer cloud save."),
            "auto_upload_gog_on_exit",
            T("Automatic cloud upload on exit will be OFF for your GOG library games. Their saves won't be pushed to GOG cloud when a game closes — you'll need to upload them yourself from the GOG Save Manager tab or the game's detail page. Continue?")),
        saveToggle("app.saves.gogDownload", T("GOG games"), T("Auto-download from GOG cloud on launch"), T("Newest wins: never overwrites a newer local save."),
            "auto_download_gog_on_launch",
            T("Automatic cloud download on launch will be OFF for your GOG library games. The latest cloud saves won't be pulled in before a game starts — you'll need to download them yourself from the GOG Save Manager tab or the game's detail page. Continue?")),
        saveToggle("app.saves.customBackup", T("Your own games"), T("Auto-back up on exit"), T("Snapshot custom-import saves to the local vault when a game exits."),
            "auto_backup_custom_on_exit",
            T("Automatic save backup on exit will be OFF for your custom-imported games. Their saves won't be captured when a game closes — you'll need to back them up yourself via a container's backup option or the game's ⋮ menu → 'Back up saves'. Continue?")),
        // Writes to the real Steam account, so turning it ON is the guarded step.
        SettingDef(
            id = "app.saves.achievementSync", category = "app.saves", group = T("Steam achievements"),
            label = T("Sync unlocks to your Steam profile"), level = ADVANCED, tech = "achievement_syncback_enabled",
            hint = T("Achievements you unlock in-game are written back to your real Steam account. Off: unlocks stay on this device."),
            control = { _, _ -> Control.Toggle },
            app = AppBinding({ c -> onOff(SteamPrefs.isAchievementSyncBackEnabled(c)) }, { c, v -> SteamPrefs.setAchievementSyncBackEnabled(c, v == "1") }),
            confirm = { v ->
                if (v == "1") Confirm(
                    T("Sync achievements to Steam?"),
                    T("Turning this ON writes achievements you unlock in-game back to your REAL Steam account and profile. This is a third-party sync — it can't be undone on Steam and may not match what official Steam would record. Leave it off to keep unlocks local to this device. Turn on anyway?"),
                    T("Turn on"),
                ) else null
            },
        ),

        // Contents › Contents settings
        app(
            "app.contents.keepRaw", "app.contents", T("Downloads"), T("Keep raw archive by default"), ADVANCED,
            hint = T("Save every install's download to My Files."), tech = "keep_raw",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(ComponentLibrary(c).keepRaw()) }, { c, v -> ComponentLibrary(c).setKeepRaw(v == "1") }),
        ),
        app(
            "app.contents.showOfficial", "app.contents", T("Repositories"), T("Show Official catalog in repositories"), ADVANCED,
            hint = T("Off hides it in Contents; the container and game sheets keep it."), tech = "show_official",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(RemoteSourceRepository(c).showOfficial()) }, { c, v -> RemoteSourceRepository(c).setShowOfficial(v == "1") }),
        ),

        // Input Controls › Device and › Assign
        app(
            "app.controllers.steamController", "app.controllers", T("Steam Controller"), T("Steam Controller support"), SIMPLE,
            hint = T("Reads a Steam Controller directly (its trackpads and extra buttons). Off: the normal input path is unchanged."), tech = "steam_controller_sdl_enabled",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(GlobalControllerPrefs.isSteamControllerEnabled(c)) }, { c, v -> GlobalControllerPrefs.setSteamControllerEnabled(c, v == "1") }),
        ),
        SettingDef(
            id = "app.controllers.trackpadMouse", category = "app.controllers", group = T("Steam Controller"), label = T("Trackpad mouse"), level = ADVANCED,
            hint = T("Which trackpad moves the mouse. Clicking it is a left click."), tech = "steam_controller_trackpad_mode",
            control = { _, _ ->
                Control.Segmented(listOf(
                    Opt("${SteamControllerBackend.TRACKPAD_MOUSE_RIGHT}", T("Right")),
                    Opt("${SteamControllerBackend.TRACKPAD_MOUSE_LEFT}", T("Left")),
                    Opt("${SteamControllerBackend.TRACKPAD_MOUSE_BOTH}", T("Both")),
                    Opt("${SteamControllerBackend.TRACKPAD_MOUSE_OFF}", T("Off")),
                ))
            },
            app = AppBinding(
                get = { c -> GlobalControllerPrefs.getSteamTrackpadMouseMode(c).toString() },
                set = { c, v -> v.toIntOrNull()?.let { GlobalControllerPrefs.setSteamTrackpadMouseMode(c, it) } },
            ),
            visible = { _, values -> values("app.controllers.steamController") == "1" },
        ),
        app(
            "app.controllers.onScreenPriority", "app.controllers", T("New containers start with"), T("On-screen controls vs. a real pad"), ADVANCED,
            hint = T("Copied into each new container; existing containers keep their own."), tech = "global_on_screen_controller_mode",
            control = {
                Control.Segmented(listOf(
                    Opt("${Container.ON_SCREEN_MODE_KEEP}", T("On-screen stays Player 1")),
                    Opt("${Container.ON_SCREEN_MODE_YIELD}", T("Pad becomes Player 1")),
                    Opt("${Container.ON_SCREEN_MODE_SHARE}", T("Both control Player 1")),
                ))
            },
            binding = AppBinding(
                get = { c -> GlobalControllerPrefs.getOnScreenMode(c).toString() },
                set = { c, v -> v.toIntOrNull()?.let { GlobalControllerPrefs.setOnScreenMode(c, it) } },
            ),
        ),
        app(
            "app.controllers.autoHide", "app.controllers", T("New containers start with"), T("Hide on-screen controls when a controller connects"), ADVANCED,
            hint = T("Copied into each new container; existing containers keep their own."), tech = "global_auto_hide_controls_on_pad",
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
        confirm = { v -> if (v == "0") Confirm(T("Turn off auto-backup?"), offWarning, T("Continue")) else null },
    )

    private fun saveManagerPrefs(c: Context) = c.getSharedPreferences("save_manager_prefs", Context.MODE_PRIVATE)

    /** An app-wide power default. Games can override it in their own editor (Performance › Power). */
    private fun perf(id: String, label: String, hint: String?, get: () -> Boolean, set: (Boolean) -> Unit) = app(
        id, "app.performance", T("Power (defaults for every game)"), label, ADVANCED, hint = hint,
        control = { Control.Toggle },
        binding = AppBinding(get = { _ -> onOff(get()) }, set = { _, v -> set(v == "1") }),
    )
}
