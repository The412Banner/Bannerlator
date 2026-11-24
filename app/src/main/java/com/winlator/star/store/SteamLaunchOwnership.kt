package com.winlator.star.store

/**
 * What [SteamLaunchOwnership.decide] says a SteamLite / Headless Steam launch should do about the
 * account the game belongs to. Pure: the caller applies the link and the remembered-choice side effects.
 */
internal sealed class LaunchDecision {
    /**
     * Go on with the signed-in account. [linkOwner] is the account to record as the game's owner (a
     * snapshot or the DB named it), or null when no link needs recording: the game's recorded account
     * is the signed-in one, or the user's remembered "launch anyway" choice applies.
     */
    data class Proceed(val linkOwner: Long?) : LaunchDecision()

    /** The game belongs to saved account [owner], which is not the signed-in one: offer the switch. */
    data class SwitchTo(val owner: Long) : LaunchDecision()

    /** No saved account is known to own the game; the launcher asks the user instead of guessing. */
    object NoOwner : LaunchDecision()
}

/**
 * Which Steam account a SteamLite / Headless Steam launch belongs to, decided without Android.
 *
 * Order matters. A recorded link is the account that downloaded the game or was found at an earlier
 * launch. Otherwise the saved accounts' owned-game snapshots are read, the signed-in account first and
 * then the most recently used others. With neither, the DB may still know the game for the only saved
 * account (the legacy/unknown-owner case). The user's per-game "launch anyway" choice only decides the
 * case where no owner is found at all: an owner found later always wins, so the caller can clear that
 * choice. Ownership that is merely unknown is still [LaunchDecision.NoOwner] — the launcher asks, it
 * never assumes.
 */
internal object SteamLaunchOwnership {

    fun decide(
        appId: Int,
        active: Long,
        saved: List<Long>,
        recordedOwner: Long,
        owned: Map<Long, Set<Int>?>,
        dbProvesActive: Boolean,
        remembered: Boolean,
    ): LaunchDecision {
        if (recordedOwner != 0L && saved.contains(recordedOwner)) {
            return if (recordedOwner == active) LaunchDecision.Proceed(null)
            else LaunchDecision.SwitchTo(recordedOwner)
        }
        val snapshotOwner = snapshotOwner(appId, active, saved, owned)
        if (snapshotOwner != 0L) {
            return if (snapshotOwner == active) LaunchDecision.Proceed(active)
            else LaunchDecision.SwitchTo(snapshotOwner)
        }
        // An owning account outranks a remembered "launch anyway": finding it makes that choice obsolete.
        if (dbProvesActive) return LaunchDecision.Proceed(active)
        if (remembered) return LaunchDecision.Proceed(null)
        return LaunchDecision.NoOwner
    }

    /**
     * The first saved account whose owned-game snapshot contains [appId]: the signed-in [active] one
     * before [saved]'s most-recent-first order. 0 when no snapshot is known to own it; a null map value
     * means that account has no snapshot yet (unknown, never "owns nothing").
     */
    fun snapshotOwner(appId: Int, active: Long, saved: List<Long>, owned: Map<Long, Set<Int>?>): Long {
        if (owned[active]?.contains(appId) == true) return active
        for (sid in saved) {
            if (sid != active && owned[sid]?.contains(appId) == true) return sid
        }
        return 0L
    }
}
