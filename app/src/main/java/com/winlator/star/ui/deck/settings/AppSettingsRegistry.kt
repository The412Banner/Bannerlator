package com.winlator.star.ui.deck.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.preference.PreferenceManager
import com.winlator.star.box64.Box64Preset
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.UpdateManager
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.midi.MidiManager
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import com.winlator.star.ui.deck.settings.SettingLevel.EXPERT
import com.winlator.star.ui.deck.settings.SettingLevel.SIMPLE
import java.util.Locale

// App-wide settings for the Deck Settings tab. Each one reads and writes the same preference the
// classic Settings screen uses, and applies the moment it changes (there is nothing to save). What is
// not ported (storage folders, logs, backups, Steam network, Lossless.dll) is one hand-off away.

internal object AppSettingsRegistry {

    val categories: List<SettingsCategory> = listOf(
        SettingsCategory("app.general", "General", "Start-up screen and updates.", Icons.Filled.Tune),
        SettingsCategory("app.appearance", "Appearance", "Theme, accent colour, size and orientation.", Icons.Filled.Palette),
        SettingsCategory("app.library", "Games & library", "Cover art and the default MIDI sound bank.", Icons.AutoMirrored.Filled.LibraryBooks),
        SettingsCategory("app.performance", "Performance", "Power defaults for every game and translator presets.", Icons.Filled.Speed),
        SettingsCategory("app.network", "Downloads & network", "Store download engines, speed and Steam connection.", Icons.Filled.CloudDownload),
        SettingsCategory("app.storage", "Storage", "Space used, app folders and the base system.", Icons.Filled.Storage),
        SettingsCategory("app.logs", "Logs & diagnostics", "What gets recorded, where it goes, how long it's kept.", Icons.Filled.Description),
        SettingsCategory("app.backup", "Backup & restore", "All app data, game saves and containers.", Icons.Filled.Archive),
        SettingsCategory("app.controllers", "Controllers", "Mouse and keyboard behaviour for every game.", Icons.Filled.Gamepad),
        SettingsCategory("app.about", "About & help", "Version, credits, help and community links.", Icons.Filled.Info),
        SettingsCategory("app.advanced", "Advanced", "Experimental switches and display server internals.", Icons.Filled.Science),
    )

    const val TARGET_CLASSIC = "classicSettings"
    const val TARGET_APPEARANCE = "appearance"
    const val TARGET_LOGS = "logs"
    const val TARGET_ABOUT = "about"

    val handoffs: Map<String, List<Handoff>> = mapOf(
        "app.general" to listOf(Handoff("Check for updates", "Version, release notes and Download & install (classic Settings).", TARGET_CLASSIC)),
        "app.appearance" to listOf(Handoff("Theme and colours", "16 themes, custom accent, light or dark, interface and text size, orientation.", TARGET_APPEARANCE)),
        "app.library" to listOf(Handoff("Installed sound banks and export folder", "Add .sf2 files and choose where shortcuts are exported (classic Settings).", TARGET_CLASSIC)),
        "app.performance" to listOf(
            Handoff("Root controls, heat safety and Lossless.dll", "CPU governor and frequency locks, thermal auto-revert, and importing Lossless.dll for frame generation (classic Settings).", TARGET_CLASSIC),
            Handoff("Edit translator presets", "Add, copy, delete, export or import Box64 and FEX presets (classic Settings).", TARGET_CLASSIC),
        ),
        "app.network" to listOf(Handoff("Downloads and Steam connection", "Download speed, download engines, Steam server region and network test (classic Settings).", TARGET_CLASSIC)),
        "app.storage" to listOf(Handoff("Storage and folders", "App data folder, base system (ImageFS) and space used (classic Settings).", TARGET_CLASSIC)),
        "app.logs" to listOf(Handoff("Open the Log Manager", "Choose what is recorded, where logs go and how many are kept; browse and share them.", TARGET_LOGS)),
        "app.backup" to listOf(Handoff("Back up or restore all app data", "Containers, settings and prefix in one file (classic Settings).", TARGET_CLASSIC)),
        "app.controllers" to listOf(Handoff("Steam Controller and gyro calibration", "Steam Controller support and calibrating the gyroscope (classic Settings).", TARGET_CLASSIC)),
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
            hint = "Shows the update banner at the top.", tech = "Notify me about updates",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(UpdateManager.isNotifyEnabled(c)) }, { c, v -> UpdateManager.setNotifyEnabled(c, v == "1") }),
        ),
        app(
            "app.general.updatePrerelease", "app.general", "Updates", "Include beta builds", ADVANCED,
            hint = "Pre-releases may be less stable.", tech = "Include pre-releases",
            control = { Control.Toggle },
            binding = AppBinding({ c -> onOff(UpdateManager.isIncludePrereleases(c)) }, { c, v -> UpdateManager.setIncludePrereleases(c, v == "1") }),
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
                get = { c -> prefs(c).getString(MidiManager.PREF_DEFAULT_SOUND_FONT, "") ?: "" },
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
            "app.controllers.cursorSpeed", "app.controllers", "Mouse & keyboard", "Cursor speed", ADVANCED, tech = "cursor_speed",
            control = { Control.Slider(0.1f, 2.0f, 0.05f, { "${(it * 100).toInt()}%" }, { String.format(Locale.ENGLISH, "%.2f", it) }) },
            binding = AppBinding(
                get = { c -> String.format(Locale.ENGLISH, "%.2f", prefs(c).getFloat("cursor_speed", 1.0f)) },
                set = { c, v -> v.toFloatOrNull()?.let { prefs(c).edit().putFloat("cursor_speed", it).apply() } },
            ),
        ),
        toggle("app.controllers.trueMouse", "app.controllers", "Mouse & keyboard", "True mouse control", ADVANCED, "cursor_lock", false,
            hint = "Locks the pointer to the game. Press Volume Down to release."),
        toggle("app.controllers.disableXinput", "app.controllers", "Mouse & keyboard", "Exclusive mouse & keyboard", ADVANCED, "xinput_toggle", false,
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
    )

    /** An app-wide power default. Games can override it in their own editor (Performance › Power). */
    private fun perf(id: String, label: String, hint: String?, get: () -> Boolean, set: (Boolean) -> Unit) = app(
        id, "app.performance", "Power (defaults for every game)", label, ADVANCED, hint = hint,
        control = { Control.Toggle },
        binding = AppBinding(get = { _ -> onOff(get()) }, set = { _, v -> set(v == "1") }),
    )
}
