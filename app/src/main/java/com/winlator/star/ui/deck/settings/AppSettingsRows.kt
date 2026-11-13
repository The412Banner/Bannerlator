package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.T
import android.content.Context
import androidx.preference.PreferenceManager
import com.winlator.star.core.AppOrientation
import com.winlator.star.store.SteamRegion
import com.winlator.star.store.StoreDownloadTier
import com.winlator.star.store.blsteam.BlSteamEngineFlag
import com.winlator.star.store.blsteam.BlStoreEngineFlag
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import com.winlator.star.ui.deck.settings.SettingLevel.SIMPLE
import com.winlator.star.ui.screens.LogCopy
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.themePresets
import java.util.Locale
import kotlin.math.roundToInt

// The app-settings rows that only the XMB Settings tab has (the Classic Settings screen keeps its own controls for the
// same preferences; the plain Deck and Lab never see these rows). They sit in category ids that are NOT in AppSettingsRegistry.categories
// ("app.x.*"), so SimpleOneMenuSettings, which walks that list, ignores them; AppSettingsLayout files them into the tabs.
// Each reads and writes the same preference the classic screen that owns it uses and applies the moment it changes.

internal object AppSettingsRows {

    const val CAT_APPEARANCE = "app.x.appearance"
    const val CAT_STEAM = "app.x.steam"
    const val CAT_DOWNLOADS = "app.x.downloads"

    // The values of the interface-style row, in the order of the segmented control.
    const val STYLE_CLASSIC = "classic"
    const val STYLE_DECK = "deck"
    const val STYLE_XMB = "xmb"
    const val STYLE_LAB = "lab"

    const val ID_STYLE = "app.appearance.style"

    private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)
    private fun onOff(b: Boolean) = if (b) "1" else "0"

    private fun prefBool(key: String, default: Boolean) = AppBinding(
        get = { c -> onOff(prefs(c).getBoolean(key, default)) },
        set = { c, v -> prefs(c).edit().putBoolean(key, v == "1").apply() },
    )

    private fun row(
        id: String, category: String, group: String, label: String, level: SettingLevel,
        hint: String? = null, help: String? = null, tech: String? = null,
        control: (SettingsEnv) -> Control, binding: AppBinding,
        visible: (SettingsEnv, (String) -> String) -> Boolean = { _, _ -> true },
        disabled: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
    ) = SettingDef(
        id = id, category = category, group = group, label = label, hint = hint, help = help, tech = tech, level = level,
        control = { env, _ -> control(env) }, app = binding, visible = visible, disabled = disabled,
    )

    private fun switch(
        id: String, category: String, group: String, label: String, level: SettingLevel, key: String, default: Boolean,
        hint: String? = null, help: String? = null,
        disabled: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
    ) = row(
        id, category, group, label, level, hint = hint, help = help, tech = key,
        control = { Control.Toggle }, binding = prefBool(key, default), disabled = disabled,
    )

    // ── Interface style: the same calls, in the same order, as AppearanceScreen ──
    private fun styleNow(): String {
        return when {
            AppThemeState.uiStyle.value != AppThemeState.UI_STYLE_DECK -> STYLE_CLASSIC
            AppThemeState.deckXmbHome.value -> STYLE_XMB
            AppThemeState.simpleHome.value -> STYLE_LAB
            else -> STYLE_DECK
        }
    }

    private fun setStyle(style: String) {
        when (style) {
            STYLE_CLASSIC -> AppThemeState.setUiStyle(AppThemeState.UI_STYLE_CLASSIC)
            STYLE_DECK -> {
                AppThemeState.setDeckXmbHome(false)
                AppThemeState.setSimpleHome(false)
                AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK)
            }
            STYLE_XMB -> {
                AppThemeState.setDeckXmbHome(true)
                AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK)
            }
            else -> {
                AppThemeState.setDeckXmbHome(false)
                AppThemeState.setSimpleHome(true)
                AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK)
            }
        }
    }

    private fun scale(id: String, label: String, hint: String, get: () -> Float, set: (Float) -> Unit) = row(
        id, CAT_APPEARANCE, T("Size and orientation"), label, ADVANCED, hint = hint,
        control = { Control.Slider(0.5f, 1.5f, 0.05f, { "${(it * 100).roundToInt()}%" }, { String.format(Locale.ENGLISH, "%.2f", it) }) },
        binding = AppBinding(
            get = { _ -> String.format(Locale.ENGLISH, "%.2f", get()) },
            set = { _, v -> v.toFloatOrNull()?.let { set(it) } },
        ),
    )

    fun defs(): List<SettingDef> = listOf(
        // ── Appearance (Settings > Appearance; AppearanceScreen keeps serving the Classic shell) ──
        row(
            ID_STYLE, CAT_APPEARANCE, T("Interface style"), T("Interface style"), SIMPLE,
            hint = T("Classic keeps the side menu, Deck is the console-style tab layout, XMB adds the full-screen home, Lab is the full-screen launcher."),
            help = T("Classic keeps the side menu. Deck is a console-style layout with tabs across the top, made for controllers. XMB is Deck with a full-screen XMB home: the tabs float over every page (switch them with L1/R1 or by touch). Lab is a full-screen launcher for controllers with your game art as the background. Your theme and accent apply to all four. The change applies at once."),
            control = { Control.Segmented(listOf(Opt(STYLE_CLASSIC, T("Classic")), Opt(STYLE_DECK, T("Deck")), Opt(STYLE_XMB, T("XMB")), Opt(STYLE_LAB, T("Lab")))) },
            binding = AppBinding(get = { _ -> styleNow() }, set = { _, v -> setStyle(v) }),
        ),
        row(
            "app.appearance.labLook", CAT_APPEARANCE, T("Interface style"), T("Lab look"), ADVANCED,
            hint = T("Dark or light for the Lab launcher."),
            control = { Control.Segmented(listOf(Opt("dark", T("Dark")), Opt("light", T("Light")))) },
            binding = AppBinding(
                get = { _ -> if (AppThemeState.labLight) "light" else "dark" },
                set = { _, v -> AppThemeState.setLabLight(v == "light") },
            ),
            visible = { _, values -> values(ID_STYLE) == STYLE_LAB },
        ),
        row(
            "app.appearance.preset", CAT_APPEARANCE, T("Theme"), T("Theme preset"), SIMPLE,
            hint = T("The colours of every screen. Picking a colour under Custom accent colour switches to the Custom preset."),
            control = { Control.Select(themePresets.map { Opt(it.name, it.name) }) },
            binding = AppBinding(
                get = { _ -> themePresets.getOrElse(AppThemeState.presetIndex.value) { themePresets.first() }.name },
                set = { _, v ->
                    val i = themePresets.indexOfFirst { it.name == v }
                    if (i >= 0) AppThemeState.setPreset(i)
                },
            ),
        ),
        row(
            "app.appearance.orientation", CAT_APPEARANCE, T("Size and orientation"), T("App orientation"), ADVANCED,
            hint = T("Applies to the app's own screens only: games keep their own orientation. Auto follows the device."),
            control = { Control.Segmented(listOf(Opt(AppOrientation.AUTO, T("Auto")), Opt(AppOrientation.PORTRAIT, T("Portrait")), Opt(AppOrientation.LANDSCAPE, T("Landscape")))) },
            binding = AppBinding(
                get = { c -> AppOrientation.mode(c) },
                set = { c, v ->
                    AppOrientation.setMode(c, v)
                    (c as? android.app.Activity)?.let { AppOrientation.apply(it) }
                },
            ),
            // The XMB home keeps its own lock (landscape, or portrait from the rotate button in its bars).
            visible = { _, values -> values(ID_STYLE) != STYLE_XMB },
        ),
        scale("app.appearance.uiScale", T("Interface scale"), T("Scales the app's own screens: games keep their own size."),
            { AppThemeState.uiScale.value }, { AppThemeState.setUiScale(it) }),
        scale("app.appearance.fontScale", T("Font size"), T("Scales the app's own text."),
            { AppThemeState.fontScale.value }, { AppThemeState.setFontScale(it) }),
        row(
            "app.appearance.showStores", CAT_APPEARANCE, T("Side menu and Stores"), T("Show game stores"), ADVANCED,
            hint = T("The GOG, Epic, Amazon and Steam shortcuts (the side menu, the Stores tab)."),
            control = { Control.Toggle },
            binding = AppBinding({ _ -> onOff(AppThemeState.showStores.value) }, { _, v -> AppThemeState.setShowStores(v == "1") }),
        ),
        row(
            "app.appearance.showInternal", CAT_APPEARANCE, T("Side menu and Stores"), T("Show internal storage"), ADVANCED,
            hint = T("The storage bar at the bottom of the side menu."),
            control = { Control.Toggle },
            binding = AppBinding({ _ -> onOff(AppThemeState.showInternalStorage.value) }, { _, v -> AppThemeState.setShowInternalStorage(v == "1") }),
        ),
        row(
            "app.appearance.showSd", CAT_APPEARANCE, T("Side menu and Stores"), T("Show SD card storage"), ADVANCED,
            hint = T("Only appears when a card is in the device."),
            control = { Control.Toggle },
            binding = AppBinding({ _ -> onOff(AppThemeState.showSdStorage.value) }, { _, v -> AppThemeState.setShowSdStorage(v == "1") }),
        ),

        // ── Steam connection region (the test actions are page extras: AppSettingsExtras) ──
        row(
            "app.steam.region", CAT_STEAM, T("Steam connection"), T("Steam connection region"), ADVANCED,
            hint = T("Which Steam datacenter the store, downloads and the in-game Steam session connect to. Auto uses the nearest by ping."),
            help = T("Auto pings each datacenter and keeps the fastest for a day, re-testing after a failed connect. Pick a specific one only if Auto keeps choosing badly. The store applies it on its next connect."),
            control = { Control.Select(listOf(Opt(SteamRegion.AUTO, T("Auto (nearest by ping)"))) + SteamRegion.CATALOG.map { Opt(it.code, it.name + " (" + it.code + ")") }) },
            binding = AppBinding(get = { c -> SteamRegion.mode(c) }, set = { c, v -> SteamRegion.setMode(c, v) }),
        ),

        // ── Download engines and speed (were inside the Log Manager) ──
        switch("app.downloads.steamEngine", CAT_DOWNLOADS, T("Download engines"), T("Native Steam engine (restart required)"), ADVANCED,
            BlSteamEngineFlag.PREF_KEY, BlSteamEngineFlag.DEFAULT,
            hint = T("On by default: the whole Steam session (sign-in, library, downloads, cloud, achievements, friends) runs on the native engine. Off: the legacy Java engine."),
            help = LogCopy.RUST_ENGINE),
        switch("app.downloads.epicEngine", CAT_DOWNLOADS, T("Download engines"), T("Native engine: Epic downloads"), ADVANCED,
            BlStoreEngineFlag.PREF_KEY_EPIC, BlStoreEngineFlag.DEFAULT,
            hint = T("On by default. Applies to the next download. Off: the Java downloader."), help = LogCopy.RUST_STORE_ENGINES),
        switch("app.downloads.gogEngine", CAT_DOWNLOADS, T("Download engines"), T("Native engine: GOG downloads"), ADVANCED,
            BlStoreEngineFlag.PREF_KEY_GOG, BlStoreEngineFlag.DEFAULT,
            hint = T("On by default. Applies to the next download. Off: the Java downloader."), help = LogCopy.RUST_STORE_ENGINES),
        switch("app.downloads.amazonEngine", CAT_DOWNLOADS, T("Download engines"), T("Native engine: Amazon downloads"), ADVANCED,
            BlStoreEngineFlag.PREF_KEY_AMAZON, BlStoreEngineFlag.DEFAULT,
            hint = T("On by default. Applies to the next download. Off: the Java downloader."), help = LogCopy.RUST_STORE_ENGINES),
        row(
            "app.downloads.speedTier", CAT_DOWNLOADS, T("Download engines"), T("Store download speed"), ADVANCED,
            hint = T("The ceiling for the Epic, GOG and Amazon native engines. A weak connection settles below it. Applies to the next download."),
            help = LogCopy.RUST_STORE_ENGINES, tech = StoreDownloadTier.PREF_KEY,
            control = { Control.Select(StoreDownloadTier.ALL.map { Opt(it.toString(), StoreDownloadTier.label(it)) }) },
            binding = AppBinding(
                get = { c -> StoreDownloadTier.get(c).toString() },
                set = { c, v -> v.toIntOrNull()?.let { StoreDownloadTier.set(c, it) } },
            ),
        ),
    )
}
