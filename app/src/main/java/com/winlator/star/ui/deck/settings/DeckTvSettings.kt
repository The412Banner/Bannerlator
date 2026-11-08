package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import android.view.Display
import com.winlator.star.display.ExternalDisplay
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import com.winlator.star.ui.deck.settings.SettingLevel.SIMPLE

// The classic game editor's TV tab (display.ExternalDisplay) as Display rows: start the game on a
// connected external screen, the output mode the TV is asked to run at, and whether the game renders at
// the TV's resolution. Per game only, with no container level, in the same three extras the classic tab
// writes ("tvLaunch" and "tvMatchRes" "1" / "0", "tvModeId" a mode id with 0 = leave the TV as it is).
// An absent extra reads as the classic default, so a row writes its key only once it is changed.
//
// Everything about the screen itself (its name, its modes, its HDR) is read live and never stored. As in
// the classic editor the rows show while a screen is plugged in, or while the game was already set to
// start on one when the editor opened, so a setting a since-unplugged TV left behind can still be turned
// off. HDR is reported under the switch, not asked: the session gets it from the screen it lands on.

internal object TvRows {

    const val ID_LAUNCH = "display.tv.launch"
    const val ID_MODE = "display.tv.mode"
    const val ID_MATCH_RES = "display.tv.matchRes"

    private const val GROUP = "TV and external display"

    /** The connected external screen, or null. Asked again on every read: the cable can come and go. */
    private fun display(env: SettingsEnv): Display? = runCatching { ExternalDisplay.find(env.context) }.getOrNull()

    /** A game only, and only while a screen is plugged in or the game was set to start on one when the editor opened. */
    private fun shown(env: SettingsEnv): Boolean =
        env.scope == EditorScope.GAME &&
            (display(env) != null || runCatching { ExternalDisplay.launchOnTv(env.shortcut) }.getOrDefault(false))

    /** A per-game "1" / "0" extra with no container level: absent is off. */
    private fun onOff(key: String) = GameBinding(
        keys = listOf(key),
        read = { e -> e.get(key)?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
        write = { e, v -> e.put(key, v) },
        inherit = Inherit.BUILT_IN, inherited = { "0" },
    )

    fun defs(): List<SettingDef> = listOf(
        SettingDef(
            id = ID_LAUNCH, category = "display", group = GROUP, level = SIMPLE,
            label = L("Launch this game on the TV"), hint = ExternalDisplay.HELP_LAUNCH, tech = ExternalDisplay.EXTRA_LAUNCH,
            help = ExternalDisplay.HELP_LAUNCH + "\n\n" + ExternalDisplay.NOTE_UNPLUG + "\n\n" + ExternalDisplay.NOTE_TOUCH,
            control = { _, _ -> Control.Toggle },
            game = onOff(ExternalDisplay.EXTRA_LAUNCH),
            visible = { env, _ -> shown(env) },
            worlds = WINE_LINUX,
            // The screen as Android describes it right now, and what it means for HDR.
            note = { env, _ ->
                val d = display(env)
                if (d == null) L("Nothing is plugged in. This game is still set to start on a TV.")
                else runCatching {
                    val hdr = ExternalDisplay.hdrUnavailableReason(d)
                        ?: L("HDR: automatic. This screen accepts HDR10, so HDR output switches on for this game.")
                    "${ExternalDisplay.title(d)} · ${ExternalDisplay.summary(d)}\n$hdr"
                }.getOrNull()
            },
        ),
        SettingDef(
            id = ID_MODE, category = "display", group = GROUP, level = ADVANCED,
            label = L("Output mode"), hint = L("What the TV is told to run at."), tech = ExternalDisplay.EXTRA_MODE_ID,
            control = { env, values ->
                val modes = runCatching { ExternalDisplay.selectableModes(display(env)) }.getOrDefault(emptyList())
                val opts = listOf(Opt("0", L("Default (leave the TV as it is)"))) +
                    modes.map { Opt("${it.modeId}", ExternalDisplay.modeLabel(it)) }
                val current = values(ID_MODE)
                // A mode stored for a TV that isn't plugged in stays listed, so the field never reads blank.
                Control.Select(if (opts.any { it.value == current }) opts else opts + Opt(current, "Mode $current (not on this screen)"))
            },
            game = GameBinding(
                keys = listOf(ExternalDisplay.EXTRA_MODE_ID),
                read = { e -> e.get(ExternalDisplay.EXTRA_MODE_ID)?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.toString() ?: "0" } },
                write = { e, v -> e.put(ExternalDisplay.EXTRA_MODE_ID, v) },
                inherit = Inherit.BUILT_IN, inherited = { "0" },
            ),
            visible = { env, _ -> shown(env) },
            disabled = { env, _ ->
                val d = display(env)
                val modes = runCatching { ExternalDisplay.selectableModes(d) }.getOrDefault(emptyList())
                when {
                    d == null -> L("Plug a screen in to choose an output mode.")
                    modes.size <= 1 -> L("This screen advertises one output mode") +
                        (ExternalDisplay.modeLabel(modes.firstOrNull()).takeIf { it.isNotEmpty() }?.let { ": $it." } ?: ".")
                    else -> null
                }
            },
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = ID_MATCH_RES, category = "display", group = GROUP, level = ADVANCED,
            label = L("Match the TV's resolution"), hint = ExternalDisplay.HELP_MATCH_RES, tech = ExternalDisplay.EXTRA_MATCH_RES,
            control = { _, _ -> Control.Toggle },
            game = onOff(ExternalDisplay.EXTRA_MATCH_RES),
            visible = { env, _ -> shown(env) },
            note = { env, values ->
                val tvRes = runCatching {
                    ExternalDisplay.resolutionOf(ExternalDisplay.effectiveMode(display(env), values(ID_MODE).toIntOrNull() ?: 0))
                }.getOrDefault("")
                val game = values("display.resolution")
                if (tvRes.isEmpty() || game.isEmpty()) null
                else "Renders at ${tvRes.replace("x", " × ")} instead of this game's ${game.replace("x", " × ")}. More pixels costs frame rate."
            },
            worlds = WINE_LINUX,
        ),
    )
}
