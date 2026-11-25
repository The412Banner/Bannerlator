package com.winlator.star.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure launch-ownership decisions behind [SteamAccountStore.launchAccountCheck]. */
class SteamLaunchOwnershipTest {

    @Test
    fun recordedLinkToActive_proceedsWithoutRelinking() {
        assertEquals(
            LaunchDecision.Proceed(null),
            decide(recorded = ACTIVE, owned = mapOf(ACTIVE to emptySet(), OTHER to emptySet())),
        )
    }

    @Test
    fun recordedLinkToAnotherSavedAccount_switches() {
        // The link wins even when the active account's own snapshot lists the game.
        assertEquals(
            LaunchDecision.SwitchTo(OTHER),
            decide(recorded = OTHER, owned = mapOf(ACTIVE to setOf(APP), OTHER to null)),
        )
    }

    @Test
    fun noLink_activeSnapshotOwns_proceedsAndLinksActive() {
        assertEquals(
            LaunchDecision.Proceed(ACTIVE),
            decide(owned = mapOf(ACTIVE to setOf(APP), OTHER to emptySet())),
        )
    }

    @Test
    fun noLink_otherSnapshotOwns_switchesToOwner() {
        assertEquals(
            LaunchDecision.SwitchTo(OTHER),
            decide(owned = mapOf(ACTIVE to emptySet(), OTHER to setOf(APP))),
        )
    }

    @Test
    fun noLink_allSnapshotsKnown_noneOwns_noOwner() {
        assertEquals(
            LaunchDecision.NoOwner,
            decide(owned = mapOf(ACTIVE to setOf(OTHER_APP), OTHER to emptySet())),
        )
    }

    @Test
    fun noLink_missingSnapshot_dbProvesActive_proceedsAndLinksActive() {
        assertEquals(
            LaunchDecision.Proceed(ACTIVE),
            decide(owned = mapOf(ACTIVE to emptySet(), OTHER to null), dbProves = true),
        )
    }

    @Test
    fun noLink_allSnapshotsKnown_dbProofStillWins() {
        // A snapshot that doesn't list it cannot veto the DB proof.
        assertEquals(
            LaunchDecision.Proceed(ACTIVE),
            decide(owned = mapOf(ACTIVE to setOf(OTHER_APP), OTHER to emptySet()), dbProves = true),
        )
    }

    @Test
    fun noLink_missingSnapshot_noDbProof_noOwner() {
        // Unknown ownership is the user's call: the old silent proceed is gone.
        assertEquals(
            LaunchDecision.NoOwner,
            decide(owned = mapOf(ACTIVE to emptySet(), OTHER to null)),
        )
    }

    @Test
    fun remembered_noOwner_proceedsWithoutALink() {
        assertEquals(
            LaunchDecision.Proceed(null),
            decide(owned = mapOf(ACTIVE to emptySet(), OTHER to null), remembered = true),
        )
    }

    @Test
    fun remembered_allSnapshotsKnown_noneOwns_proceeds() {
        assertEquals(
            LaunchDecision.Proceed(null),
            decide(owned = mapOf(ACTIVE to setOf(OTHER_APP), OTHER to emptySet()), remembered = true),
        )
    }

    @Test
    fun remembered_ownerFoundLater_ownerWinsOverTheChoice() {
        // The caller records the owner and clears the remembered choice in both cases.
        assertEquals(
            LaunchDecision.Proceed(ACTIVE),
            decide(owned = mapOf(ACTIVE to setOf(APP), OTHER to null), remembered = true),
        )
        assertEquals(
            LaunchDecision.SwitchTo(OTHER),
            decide(owned = mapOf(ACTIVE to emptySet(), OTHER to setOf(APP)), remembered = true),
        )
    }

    @Test
    fun remembered_dbProofStillWinsAndClearsTheChoice() {
        assertEquals(
            LaunchDecision.Proceed(ACTIVE),
            decide(owned = mapOf(ACTIVE to emptySet(), OTHER to null), dbProves = true, remembered = true),
        )
    }

    @Test
    fun ownedSnapshotGuard_anEmptySyncKeepsANonEmptyCsv() {
        assertTrue(shouldKeepOwnedSnapshot("10,20", emptyList()))
        assertFalse(shouldKeepOwnedSnapshot("10,20", listOf(30)))
    }

    @Test
    fun ownedSnapshotGuard_anEmptySyncWritesWhenThereWasNoSnapshot() {
        assertFalse(shouldKeepOwnedSnapshot(null, emptyList()))
        assertFalse(shouldKeepOwnedSnapshot("", emptyList()))
    }

    @Test
    fun savedOrder_activeIsPreferredOverAMoreRecentlyUsedOwner() {
        assertEquals(
            LaunchDecision.Proceed(ACTIVE),
            decide(
                saved = listOf(OTHER, THIRD, ACTIVE),
                owned = mapOf(OTHER to setOf(APP), THIRD to null, ACTIVE to setOf(APP)),
            ),
        )
    }

    @Test
    fun savedOrder_theMostRecentlyUsedOwnerWinsWhenActiveDoesNotOwn() {
        assertEquals(
            LaunchDecision.SwitchTo(OTHER),
            decide(
                saved = listOf(OTHER, THIRD, ACTIVE),
                owned = mapOf(OTHER to setOf(APP), THIRD to setOf(APP), ACTIVE to emptySet()),
            ),
        )
    }

    @Test
    fun recordedLinkToARemovedAccount_fallsThroughToTheSnapshots() {
        assertEquals(
            LaunchDecision.Proceed(ACTIVE),
            decide(
                saved = listOf(ACTIVE),
                recorded = REMOVED,
                owned = mapOf(ACTIVE to setOf(APP), REMOVED to setOf(APP)),
            ),
        )
    }

    private fun decide(
        appId: Int = APP,
        active: Long = ACTIVE,
        saved: List<Long> = listOf(ACTIVE, OTHER),
        recorded: Long = 0L,
        owned: Map<Long, Set<Int>?> = emptyMap(),
        dbProves: Boolean = false,
        remembered: Boolean = false,
    ): LaunchDecision =
        SteamLaunchOwnership.decide(appId, active, saved, recorded, owned, dbProves, remembered)

    private companion object {
        const val ACTIVE = 76561198000000001L
        const val OTHER = 76561198000000002L
        const val THIRD = 76561198000000003L
        const val REMOVED = 76561198000000004L
        const val APP = 440
        const val OTHER_APP = 730
    }
}
