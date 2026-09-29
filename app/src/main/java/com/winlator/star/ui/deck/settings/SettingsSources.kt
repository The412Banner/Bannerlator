package com.winlator.star.ui.deck.settings

import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WineThemeManager
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.ExeShortcutImporter
import java.io.File
import java.util.Locale

/** Where a row's value comes from, for the label under it. */
internal enum class Origin { NONE, DEFAULT, FROM_CONTAINER, FROM_APP, SET_HERE, CONTAINER_ONLY }

/**
 * The editor screen's only view of storage. One per scope: [ContainerFormSource] for a container and
 * All containers, [GameSource] for a game, [AppSource] for app settings.
 */
internal abstract class SettingsSource(val env: SettingsEnv) {
    /** The row exists in this editor. */
    abstract fun shows(def: SettingDef): Boolean
    /** The row can be changed here (a game's view of a container-only field can't). */
    abstract fun editable(def: SettingDef): Boolean
    abstract fun value(def: SettingDef): String
    abstract fun set(def: SettingDef, value: String)
    abstract fun origin(def: SettingDef): Origin
    /** What Reset goes back to, for the "Default: …" note. Null when there is nothing to go back to. */
    abstract fun baseline(def: SettingDef): String?
    abstract fun reset(def: SettingDef)
    abstract val dirty: Boolean
    abstract fun save(onDone: (Boolean) -> Unit)
    abstract fun discard()
}

/**
 * A container, or the All-containers profile, edited through the classic editor's own form. The form
 * is the draft: Save runs the same confirm() the classic ✓ does, which writes every field through
 * applyFormTo. [defaults] is the All-containers profile for this container's architecture; a value
 * that differs from it counts as set for this container, and Reset copies the profile's value back.
 * [reload] throws the form away and loads it again from disk (Discard, and after a save).
 */
internal class ContainerFormSource(
    env: SettingsEnv,
    private val vm: ContainerDetailViewModel,
    private val defaults: ContainerDetailViewModel?,
    private val reload: () -> Unit,
) : SettingsSource(env) {

    override fun shows(def: SettingDef) = def.vm != null && (env.scope != EditorScope.DEFAULTS || def.onDefaults)
    override fun editable(def: SettingDef) = true
    override fun value(def: SettingDef) = def.vm?.get?.invoke(env, vm) ?: ""
    override fun set(def: SettingDef, value: String) { def.vm?.set?.invoke(env, vm, value) }

    override fun baseline(def: SettingDef): String? {
        if (env.scope != EditorScope.CONTAINER || !def.onDefaults) return null
        val d = defaults ?: return null
        return def.vm?.get?.invoke(env, d)
    }

    override fun origin(def: SettingDef): Origin {
        val base = baseline(def) ?: return Origin.NONE
        return if (value(def) == base) Origin.DEFAULT else Origin.SET_HERE
    }

    override fun reset(def: SettingDef) { baseline(def)?.let { set(def, it) } }

    private val dirtyState = derivedStateOf { vm.hasUnsavedChanges(vm.cpuList, vm.cpuListWoW64, desktopColor(vm)) }
    override val dirty: Boolean get() = dirtyState.value

    override fun save(onDone: (Boolean) -> Unit) {
        vm.confirm(
            resolvedGraphicsDriverConfig = vm.graphicsDriverConfig,
            resolvedDXWrapperConfig = vm.dxWrapperConfig,
            resolvedFPSCounterConfig = vm.fpsCounterConfig,
            resolvedEnvVars = vm.envVarsStr,
            resolvedCPUList = vm.cpuList,
            resolvedCPUListWoW64 = vm.cpuListWoW64,
            resolvedColorAsString = desktopColor(vm),
        ) {
            reload()
            onDone(true)
        }
    }

    override fun discard() = reload()

    companion object {
        /** The desktop colour the classic editor would save when its colour picker was never opened (the same rule as its unsaved check). */
        fun desktopColor(vm: ContainerDetailViewModel): String =
            if (vm.desktopBgTypeIndex == WineThemeManager.BackgroundType.COLOR.ordinal)
                String.format(Locale.ENGLISH, "#%06X", 0x00ffffff and vm.desktopBgColorInt)
            else "#0277bd"
    }
}

/**
 * One game. Edits go into [draft], keyed by shortcut extra (a null value removes the key on save),
 * and only keys the user actually changed are ever written. A setting the user never touched stays
 * absent from the shortcut, so the launch path keeps following the container. Reset removes the
 * game's own value. [containerVm] is the game's container form, the value an unset game follows;
 * [defaultsVm] is All containers, used to tell "From container" from "Default".
 */
internal class GameSource(
    env: SettingsEnv,
    private val shortcutState: MutableState<Shortcut>,
    private val containerVm: ContainerDetailViewModel,
    private val defaultsVm: ContainerDetailViewModel?,
) : SettingsSource(env) {

    private val shortcut: Shortcut get() = shortcutState.value
    private val draft = mutableStateMapOf<String, String?>()
    // Bumped whenever the shortcut on disk changes under us (save, or the classic editor), so reads recompose.
    private var version by mutableIntStateOf(0)

    private fun stored(key: String): String? {
        version
        val s = shortcut
        return if (key == NAME_KEY) s.name else if (s.hasExtra(key)) s.getExtra(key) else null
    }

    val extras = object : GameExtras {
        override fun get(key: String): String? = if (draft.containsKey(key)) draft[key] else stored(key)
        override fun put(key: String, value: String?) {
            if (value == stored(key)) draft.remove(key) else draft[key] = value
        }
    }

    private fun inherited(def: SettingDef): String =
        def.game?.inherited?.invoke(env) ?: def.vm?.get?.invoke(env, containerVm) ?: ""

    override fun shows(def: SettingDef) = def.game != null || (def.vm != null && def.showInGame)
    override fun editable(def: SettingDef) = def.game != null
    override fun value(def: SettingDef): String = def.game?.read?.invoke(extras) ?: inherited(def)

    override fun set(def: SettingDef, value: String) {
        val g = def.game ?: return
        // Picking the value the game already follows is not a change: it keeps following the container.
        if (g.inherit != Inherit.NONE && g.read(extras) == null && value == inherited(def)) return
        g.write(extras, value)
    }

    override fun origin(def: SettingDef): Origin {
        val g = def.game ?: return Origin.CONTAINER_ONLY
        if (g.inherit == Inherit.NONE) return Origin.NONE
        if (g.read(extras) != null) return Origin.SET_HERE
        return when (g.inherit) {
            Inherit.APP -> Origin.FROM_APP
            Inherit.BUILT_IN -> Origin.DEFAULT
            else -> {
                val base = defaultsVm?.let { d -> def.vm?.get?.invoke(env, d) } ?: g.builtIn
                if (base != null && inherited(def) != base) Origin.FROM_CONTAINER else Origin.DEFAULT
            }
        }
    }

    override fun baseline(def: SettingDef): String? =
        if (def.game == null || def.game.inherit == Inherit.NONE) null else inherited(def)

    override fun reset(def: SettingDef) { def.game?.keys?.forEach { extras.put(it, null) } }

    override val dirty: Boolean get() = draft.isNotEmpty()

    override fun save(onDone: (Boolean) -> Unit) {
        val s = shortcut
        val newName = if (draft.containsKey(NAME_KEY)) draft[NAME_KEY]?.trim() else null
        for ((k, v) in draft) if (k != NAME_KEY) s.putExtra(k, v)
        s.saveData()
        var file = s.file
        var ok = true
        if (!newName.isNullOrBlank() && newName != s.name) {
            // The same rename Game Details uses: .desktop, .lnk, icon and cover move together.
            if (ExeShortcutImporter.renameShortcutFiles(s.container, s.name, newName)) {
                file = File(s.container.desktopDir, "$newName.desktop")
            } else {
                ok = false
                Toast.makeText(env.context, "Couldn't rename: another game already uses \"$newName\".", Toast.LENGTH_LONG).show()
            }
        }
        draft.clear()
        reloadFrom(file)
        onDone(ok)
    }

    override fun discard() = draft.clear()

    /** Re-read the shortcut from disk, e.g. after the classic editor wrote it. */
    fun reloadFrom(file: File = shortcut.file) {
        runCatching { Shortcut(shortcut.container, file) }.getOrNull()?.let { shortcutState.value = it }
        version++
    }

    companion object {
        /** The game's name lives in its file name, not in an extra; the draft carries it under this key. */
        const val NAME_KEY = "\u0000name"
    }
}

/** App preferences. Every change is written straight away, so there is never anything to save. */
internal class AppSource(env: SettingsEnv) : SettingsSource(env) {
    private var version by mutableIntStateOf(0)

    override fun shows(def: SettingDef) = def.app != null
    override fun editable(def: SettingDef) = true
    override fun value(def: SettingDef): String {
        version
        return def.app?.get?.invoke(env.context) ?: ""
    }
    override fun set(def: SettingDef, value: String) {
        def.app?.set?.invoke(env.context, value)
        version++
    }
    override fun origin(def: SettingDef) = Origin.NONE
    override fun baseline(def: SettingDef): String? = null
    override fun reset(def: SettingDef) {}
    override val dirty: Boolean get() = false
    override fun save(onDone: (Boolean) -> Unit) = onDone(true)
    override fun discard() {}
}
