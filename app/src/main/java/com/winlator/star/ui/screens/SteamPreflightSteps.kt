package com.winlator.star.ui.screens

import com.winlator.star.store.SteamSessionManager.Step
import com.winlator.star.store.SteamSessionManager.StepState

// The pure parts of the XMB "Getting Steam ready" dialog (SteamPreflightDialog): the step list it draws and the few sizes it
// derives from the dialog's room. No Compose in here, so JVM tests cover them (SteamPreflightStepsTest).

/** The rows of the pre-flight list, in the order the pre-flight runs them, ending with the launch itself. */
internal enum class PreflightRowId { SIGN_IN, NETWORK, CLOUD, UPDATE, CLIENT, LAUNCH }

/** What a row's status icon shows. */
internal enum class PreflightMark { PENDING, RUNNING, DONE, NOTICE, SKIPPED, WARN }

/**
 * One row: [name] (bold), the live [detail] text the pre-flight reported for it (empty until it has reported) and whether it is the
 * [current] one (the one being worked on, or the one that stopped the pre-flight).
 */
internal data class PreflightRow(
    val id: PreflightRowId,
    val name: String,
    val mark: PreflightMark,
    val detail: String,
    val current: Boolean,
)

private fun markOf(state: StepState?, launching: Boolean): PreflightMark = when (state) {
    null -> if (launching) PreflightMark.SKIPPED else PreflightMark.PENDING   // launching: a step never reported is not waited for
    StepState.PENDING -> PreflightMark.PENDING
    StepState.RUNNING -> PreflightMark.RUNNING
    StepState.DONE -> PreflightMark.DONE
    StepState.NOTICE -> PreflightMark.NOTICE
    StepState.SKIPPED -> PreflightMark.SKIPPED
    StepState.WARN -> PreflightMark.WARN
}

/**
 * The step list for the live [states] (what [com.winlator.star.store.SteamSessionManager.PreflightListener.onStep] reported: state and
 * text per step). [appSteam] names the client row "Valve Steam client" (Headless Steam) instead of "SteamLite client". [launching]:
 * every step is done and the game is starting, so the last row runs with "Launching [gameName]…".
 *
 * The current row is the first one running; else the first that warned (the pre-flight stopped there); else the first still
 * pending (between two steps); none when everything is done.
 */
internal fun preflightRows(
    states: Map<Step, Pair<StepState, String>>,
    launching: Boolean,
    appSteam: Boolean,
    gameName: String,
): List<PreflightRow> {
    fun row(id: PreflightRowId, name: String, step: Step): PreflightRow {
        val st = states[step]
        return PreflightRow(id, name, markOf(st?.first, launching), st?.second.orEmpty(), current = false)
    }
    val rows = listOf(
        row(PreflightRowId.SIGN_IN, "Sign in", Step.SESSION),
        row(PreflightRowId.NETWORK, "Network", Step.NETWORK),
        row(PreflightRowId.CLOUD, "Cloud saves", Step.CLOUD),
        row(PreflightRowId.UPDATE, "Update check", Step.UPDATE),
        row(PreflightRowId.CLIENT, if (appSteam) "Valve Steam client" else "SteamLite client", Step.CLIENT),
        PreflightRow(
            PreflightRowId.LAUNCH, "Launch",
            if (launching) PreflightMark.RUNNING else PreflightMark.PENDING,
            if (launching) "Launching $gameName…" else "",
            current = false,
        ),
    )
    val current = rows.indexOfFirst { it.mark == PreflightMark.RUNNING }.takeIf { it >= 0 }
        ?: rows.indexOfFirst { it.mark == PreflightMark.WARN }.takeIf { it >= 0 }
        ?: rows.indexOfFirst { it.mark == PreflightMark.PENDING }.takeIf { it >= 0 }
    return rows.mapIndexed { i, r -> if (i == current) r.copy(current = true) else r }
}

/** A dialog at least this wide (dp) puts the photo beside the step list; a narrower one (portrait) stacks them. */
internal const val XMB_PREFLIGHT_SPLIT_MIN_WIDTH = 520f

/** Photo column share of the split body (the step list / fail card gets the rest). */
internal const val XMB_PREFLIGHT_PHOTO_SHARE = 0.34f

internal fun xmbPreflightSplit(dialogWidth: Float): Boolean = dialogWidth >= XMB_PREFLIGHT_SPLIT_MIN_WIDTH

/**
 * The height (dp) left for the body of a dialog of [maxHeight]: what the fixed header (title, subtitle), the footer and the body's
 * own paddings take is taken off. [compact] is the frame's short-screen mode, whose paddings are tighter. Never below 120.
 */
internal fun xmbPreflightBodyRoom(maxHeight: Float, compact: Boolean): Float =
    (maxHeight - if (compact) 144f else 172f).coerceAtLeast(120f)

/**
 * The tallest the photo may be (dp): in the split layout the body's [room] less the caption under the photo, so the photo column is
 * never taller than the step list beside it can be; stacked (portrait) a small photo on top of the list.
 */
internal fun xmbPreflightPhotoMaxHeight(room: Float, split: Boolean): Float =
    if (split) (room - 66f).coerceIn(80f, 220f) else (room * 0.22f).coerceIn(80f, 132f)

/**
 * The columns of the fail card's buttons: two when the card (the right column of a split body, [dialogWidth] dp wide in all) is
 * wide enough for "Launch with Goldberg" at half its width, else one stacked column of full-width buttons.
 */
internal fun xmbPreflightActionColumns(dialogWidth: Float, split: Boolean, count: Int): Int {
    if (count < 2 || !split) return 1
    val card = (dialogWidth - 54f) * (1f - XMB_PREFLIGHT_PHOTO_SHARE)
    return if (card >= 420f) 2 else 1
}
