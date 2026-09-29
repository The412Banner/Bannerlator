package com.winlator.star.ui.deck.settings

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.graphics.vector.ImageVector
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.isSteamOriginShortcut
import com.winlator.star.widget.XServerView
import java.io.File

// The Deck settings editor's data model. Every setting is declared once (SettingsRegistry) with the
// real storage it reads and writes: the container form (ContainerDetailViewModel, which also backs
// the All-containers defaults profile), a game's shortcut extras, or an app preference. The editor
// screen only ever talks to a SettingsSource, so the same rows serve all four scopes.

/** How much the editor shows. Simple is the handful most people touch; Expert is everything. */
internal enum class SettingLevel(val label: String) { SIMPLE("Simple"), ADVANCED("Advanced"), EXPERT("Expert") }

/** What one editor edits. DEFAULTS is "All containers", i.e. the New Container Defaults profile. */
internal enum class EditorScope { DEFAULTS, CONTAINER, GAME, APP }

internal class SettingsCategory(val id: String, val label: String, val desc: String, val icon: ImageVector)

/** One choice of a segmented, chips or select control. A non-null [disabledReason] greys it out and says why. */
internal data class Opt(val value: String, val label: String, val disabledReason: String? = null)

internal sealed interface Control {
    /** On/off. Values are "1" and "0". */
    data object Toggle : Control
    class Segmented(val options: List<Opt>) : Control
    class Chips(val options: List<Opt>) : Control
    /**
     * A drop-down list. With [customLabel] set, a last entry lets the user type a value of their own;
     * [validateCustom] returns an error message, or null when the text is fine.
     */
    class Select(
        val options: List<Opt>,
        val customLabel: String? = null,
        val customHint: String? = null,
        val validateCustom: ((String) -> String?)? = null,
        val normalizeCustom: (String) -> String = { it.trim() },
    ) : Control
    /** [display] is what the row shows; [encode] is the stored form (e.g. "80" or "2.0"). */
    class Slider(
        val min: Float,
        val max: Float,
        val step: Float,
        val display: (Float) -> String,
        val encode: (Float) -> String,
    ) : Control
    class TextField(val placeholder: String = "", val validate: (String) -> String? = { null }) : Control
    /** A tick per CPU core. The value is the comma-separated list the container and shortcut already store. */
    data object Cores : Control
}

/** Read/write access to a game's shortcut extras. [get] returns null when the key is absent; [put] with null removes it. */
internal interface GameExtras {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** A setting's value on the container form. Used for both a real container and the All-containers profile. */
internal class VmBinding(
    val get: (SettingsEnv, ContainerDetailViewModel) -> String,
    val set: (SettingsEnv, ContainerDetailViewModel, String) -> Unit,
)

/** What an unset per-game value follows, which decides the row's source label. */
internal enum class Inherit { CONTAINER, APP, BUILT_IN, NONE }

/**
 * A setting's per-game storage. [read] returns null while the game has no value of its own, and the
 * launch path then uses the container's (or the app's) value. [keys] are every extra the setting
 * owns; Reset removes all of them.
 */
internal class GameBinding(
    val keys: List<String>,
    val read: (GameExtras) -> String?,
    val write: (GameExtras, String) -> Unit,
    val inherit: Inherit = Inherit.CONTAINER,
    /** The value an unset game follows when it is not the container form's value ([inherit] APP or BUILT_IN). */
    val inherited: ((SettingsEnv) -> String)? = null,
    /** What counts as "Default" for a container-inherited value that has no container-form binding. */
    val builtIn: String? = null,
)

/** A global app preference. Written the moment it changes. */
internal class AppBinding(
    val get: (Context) -> String,
    val set: (Context, String) -> Unit,
)

/** A confirmation asked before a value is taken, e.g. the SurfaceFlinger renderer's warning. */
internal class Confirm(val title: String, val body: String, val confirmLabel: String)

internal class SettingDef(
    val id: String,
    val category: String,
    val group: String,
    val label: String,
    val hint: String? = null,
    val help: String? = null,
    /** The old label or raw key, shown small from Advanced up. */
    val tech: String? = null,
    val level: SettingLevel,
    val control: (SettingsEnv, (String) -> String) -> Control,
    val vm: VmBinding? = null,
    val game: GameBinding? = null,
    val app: AppBinding? = null,
    /** Also on the All-containers profile (some container fields, like its name, are per container only). */
    val onDefaults: Boolean = true,
    /** Shown read-only in a game's editor when it has no per-game storage. */
    val showInGame: Boolean = true,
    /** Context gates and value conditions together. [values] reads another setting's current value by id. */
    val visible: (SettingsEnv, (String) -> String) -> Boolean = { _, _ -> true },
    /** Non-null greys the control out and shows the reason under it. */
    val disabled: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
    val confirm: ((String) -> Confirm?)? = null,
)

/** A row that opens the classic editor (or another screen) for what the Deck editor does not edit itself. */
internal class Handoff(val label: String, val hint: String, val target: String)

/**
 * Everything a setting may look at to decide its options and whether it applies. Built once per
 * editor. [vm] is the container form: the game's container in a game editor, the container itself,
 * or the All-containers profile. It is null only for app settings.
 */
internal class SettingsEnv(
    val context: Context,
    val scope: EditorScope,
    val vm: ContainerDetailViewModel?,
    val shortcut: Shortcut?,
) {
    val isLinuxEntry: Boolean = shortcut != null && com.winlator.star.linux.LinuxShortcuts.isLinuxEntry(shortcut)
    val isSteam: Boolean = shortcut != null && runCatching { isSteamOriginShortcut(shortcut) }.getOrDefault(false)
    val arm64ec: Boolean get() = vm?.isArm64EC == true

    /** Whether the container's Wine layer can drive Wayland. Always true on the defaults profile, which has no layer. */
    val waylandCapable: Boolean
        get() = scope == EditorScope.DEFAULTS || vm?.let { it.isWineWaylandCapable(it.selectedWineVersion) } == true

    private val display = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.display
        else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }.getOrNull()
    val panelRates: List<Int> = display?.let { runCatching { XServerView.getSupportedRefreshRates(it) }.getOrNull() } ?: emptyList()
    val vrrCapable: Boolean = display?.let { runCatching { XServerView.isDisplayVrrCapable(it) }.getOrDefault(false) } ?: false
    val lsfgDllAvailable: Boolean = File(context.filesDir, "lsfg-vk/Lossless.dll").isFile

    // Lists read from preferences and disk. Read once per editor, not on every recomposition.
    val fexPresets by lazy { runCatching { com.winlator.star.fexcore.FEXCorePresetManager.getPresets(context).toList() }.getOrDefault(emptyList()) }
    val box64Presets by lazy { runCatching { com.winlator.star.box64.Box64PresetManager.getPresets("box64", context).toList() }.getOrDefault(emptyList()) }
    val controlsProfiles by lazy {
        runCatching { com.winlator.star.inputcontrols.InputControlsManager(context).getProfiles(true).toList() }.getOrDefault(emptyList())
    }
    val rendererDrivers by lazy {
        runCatching { com.winlator.star.contents.AdrenotoolsManager(context).enumarateInstalledDrivers().toList() }.getOrDefault(emptyList())
    }

    /** The Wayland backend is in effect: a Linux entry always, otherwise the chosen backend on a capable layer. */
    fun wayland(values: (String) -> String): Boolean =
        isLinuxEntry || (values(ID_BACKEND) == Container.DISPLAY_BACKEND_WAYLAND && waylandCapable)

    companion object {
        const val ID_BACKEND = "display.backend"
    }
}
