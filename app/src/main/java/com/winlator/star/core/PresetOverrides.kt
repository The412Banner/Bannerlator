package com.winlator.star.core

import android.content.Context
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.fexcore.FEXCorePresetManager

/**
 * Where a preset edit belongs.
 *
 * The preset LIST is shared, but the VALUES a preset carries are resolved in three tiers, matching
 * how the app already resolves which preset is selected — a shortcut reads its own value and falls
 * back to its container ([com.winlator.star.XServerDisplayActivity], `shortcut.getExtra("box64Preset",
 * container.getBox64Preset())`), and a new container is seeded from the App Settings prefs
 * ([com.winlator.star.ui.screens.ContainerDetailViewModel]). This extends the same three tiers from
 * *which preset is picked* to *what is inside it*:
 *
 * | Scope | Editing there writes to | Who follows |
 * |---|---|---|
 * | [GLOBAL] | the shared preset (the existing `<prefix>_preset_overrides` pref) | every new container; anything not customised below |
 * | [CONTAINER] | that container | its games, unless they are customised |
 * | [SHORTCUT] | that game | that game only — this wins at launch |
 *
 * Because a game screen can only ever write to that game, editing from one cannot disturb another
 * game, its container, or the shared preset — so no "this is global, careful" warning is needed.
 *
 * ── Why per-scope values live on their owner ────────────────────────────────────────────────────
 * Container values are stored on the [Container] (its `.container` JSON) and shortcut values in the
 * `.desktop` Extra Data, NOT in a global preference keyed by container/shortcut id. A keyed pref
 * would outlive the thing it describes: delete a container and its override would sit in the prefs
 * for ever, and a recycled id would silently inherit a stranger's values. Storing them on the owner
 * means they are created, copied and deleted with it, for free.
 */
enum class PresetScope { GLOBAL, CONTAINER, SHORTCUT }

/**
 * Reads and writes preset values at a [PresetScope], and resolves the effective values for a launch.
 *
 * Every entry point takes the same shape: which preset kind, which preset id, and the container /
 * shortcut in play (null when they don't apply). The chain is always shortcut → container → global,
 * and "global" itself already means *user override, else the values this build ships*.
 */
object PresetOverrides {

    /** Container field / `.desktop` Extra Data key holding local Box64 values for a preset. */
    const val KEY_BOX64_VARS = "box64PresetVars"

    /** Container field / `.desktop` Extra Data key holding local FEXCore values for a preset. */
    const val KEY_FEX_VARS = "fexcorePresetVars"

    /**
     * Local values are stored as `presetId|VARS`, so switching preset doesn't silently apply the
     * previous preset's edits. A stored entry for a different preset id is ignored (and replaced on
     * the next write), which is what makes "customise Extreme, switch to Denuvo" behave sanely.
     */
    private const val SEP = '|'

    // ── Reading ─────────────────────────────────────────────────────────────────────────────

    /**
     * The values that should actually be applied for [presetId] — shortcut, else container, else the
     * shared preset. [container] and [shortcut] may be null; a null shortcut simply skips that tier.
     *
     * This is what the launcher applies, so it is the single definition of "what this game runs with".
     */
    @JvmStatic
    fun effective(
        context: Context,
        isFex: Boolean,
        presetId: String,
        container: Container?,
        shortcut: Shortcut?,
    ): EnvVars {
        localOf(context, isFex, presetId, PresetScope.SHORTCUT, container, shortcut)?.let { return it }
        localOf(context, isFex, presetId, PresetScope.CONTAINER, container, shortcut)?.let { return it }
        return globalOf(context, isFex, presetId)
    }

    /**
     * The local values a launch should apply, or **null when nothing below the shared preset has
     * been customised** — the launcher then keeps its original preset-manager lookup, so a setup
     * that has never used this feature takes byte-for-byte the same path as before.
     */
    @JvmStatic
    fun localEffective(
        context: Context,
        isFex: Boolean,
        presetId: String,
        container: Container?,
        shortcut: Shortcut?,
    ): EnvVars? =
        localOf(context, isFex, presetId, PresetScope.SHORTCUT, container, shortcut)
            ?: localOf(context, isFex, presetId, PresetScope.CONTAINER, container, shortcut)

    /** The shared preset's values: the user's global override if any, else what this build ships. */
    @JvmStatic
    fun globalOf(context: Context, isFex: Boolean, presetId: String): EnvVars =
        if (isFex) FEXCorePresetManager.getEnvVars(context, presetId)
        else Box64PresetManager.getEnvVars("box64", context, presetId)

    /**
     * The values local to [scope] for [presetId], or null when that scope holds none — i.e. when it
     * is still inheriting. [PresetScope.GLOBAL] never returns null; the shared preset always resolves.
     */
    @JvmStatic
    fun localOf(
        context: Context,
        isFex: Boolean,
        presetId: String,
        scope: PresetScope,
        container: Container?,
        shortcut: Shortcut?,
    ): EnvVars? = when (scope) {
        PresetScope.GLOBAL -> globalOf(context, isFex, presetId)
        PresetScope.CONTAINER -> parse(container?.getPresetVars(isFex), presetId)
        PresetScope.SHORTCUT -> parse(shortcut?.getExtra(keyOf(isFex), null), presetId)
    }

    /**
     * What [scope] would fall back to if its local values were dropped — the target of Reset, and
     * what Save compares against to decide whether a local copy is needed at all.
     */
    @JvmStatic
    fun inheritedBy(
        context: Context,
        isFex: Boolean,
        presetId: String,
        scope: PresetScope,
        container: Container?,
        shortcut: Shortcut?,
    ): EnvVars = when (scope) {
        // Nothing above the shared preset but the values this build ships.
        PresetScope.GLOBAL ->
            if (isFex) FEXCorePresetManager.getShippedEnvVars(context, presetId)
            else Box64PresetManager.getShippedEnvVars("box64", context, presetId)
        PresetScope.CONTAINER -> globalOf(context, isFex, presetId)
        PresetScope.SHORTCUT ->
            localOf(context, isFex, presetId, PresetScope.CONTAINER, container, shortcut)
                ?: globalOf(context, isFex, presetId)
    }

    /**
     * True when [scope] holds its own values for [presetId] — drives the "customised" badge. For
     * [PresetScope.GLOBAL] this is the existing "edited vs shipped" flag on the shared preset.
     */
    @JvmStatic
    fun isCustomised(
        context: Context,
        isFex: Boolean,
        presetId: String,
        scope: PresetScope,
        container: Container?,
        shortcut: Shortcut?,
    ): Boolean = when (scope) {
        PresetScope.GLOBAL ->
            if (isFex) FEXCorePresetManager.hasOverride(context, presetId)
            else Box64PresetManager.hasOverride("box64", context, presetId)
        else -> localOf(context, isFex, presetId, scope, container, shortcut) != null
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────

    /**
     * Store [envVars] as [scope]'s own values for [presetId] **and persist the owner immediately**.
     *
     * Device-proven trap this avoids (2026-09-08): the preset editor is a modal with its own Save
     * button, so pressing Save reads as committed. When the write only went to memory, the
     * "customised" badge appeared while nothing reached disk, and backing out of the parent screen
     * silently discarded the edit — the UI claimed a change that did not exist. Consistency with the
     * screen's other fields matters less than consistency with *having pressed Save*, so this
     * commits on the spot.
     *
     * [PresetScope.GLOBAL] is not handled here: the shared preset keeps its existing path through
     * the preset managers, which also maintains the custom-preset list and its names.
     */
    @JvmStatic
    fun writeLocal(
        isFex: Boolean,
        presetId: String,
        scope: PresetScope,
        container: Container?,
        shortcut: Shortcut?,
        envVars: EnvVars,
    ) {
        val stored = presetId + SEP + envVars.toString()
        when (scope) {
            PresetScope.CONTAINER -> container?.let { it.setPresetVars(isFex, stored); it.saveData() }
            PresetScope.SHORTCUT -> shortcut?.let { it.putExtra(keyOf(isFex), stored); it.saveData() }
            PresetScope.GLOBAL -> Unit
        }
    }

    /** Drop [scope]'s own values so it inherits again, persisting the owner immediately — Reset
     *  commits for the same reason Save does (see [writeLocal]). */
    @JvmStatic
    fun clearLocal(
        isFex: Boolean,
        scope: PresetScope,
        container: Container?,
        shortcut: Shortcut?,
    ) {
        when (scope) {
            PresetScope.CONTAINER -> container?.let { it.setPresetVars(isFex, null); it.saveData() }
            PresetScope.SHORTCUT -> shortcut?.let { it.removeExtra(keyOf(isFex)); it.saveData() }
            PresetScope.GLOBAL -> Unit
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────

    private fun keyOf(isFex: Boolean) = if (isFex) KEY_FEX_VARS else KEY_BOX64_VARS

    /**
     * `presetId|VARS` → the vars, but only when the stored id matches the preset being asked about.
     * A mismatch means the user has since switched preset, so the stale local copy does not apply.
     */
    private fun parse(stored: String?, presetId: String): EnvVars? {
        if (stored.isNullOrEmpty()) return null
        val cut = stored.indexOf(SEP)
        if (cut <= 0) return null
        if (stored.substring(0, cut) != presetId) return null
        val vars = stored.substring(cut + 1)
        return if (vars.isEmpty()) null else EnvVars(vars)
    }
}

/** Container-side accessor pair, kept here so the two field names live in one place. */
private fun Container.getPresetVars(isFex: Boolean): String? =
    if (isFex) fexcorePresetVars else box64PresetVars

private fun Container.setPresetVars(isFex: Boolean, value: String?) {
    if (isFex) fexcorePresetVars = value else box64PresetVars = value
}
