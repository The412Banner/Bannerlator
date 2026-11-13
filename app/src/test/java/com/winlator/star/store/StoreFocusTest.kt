package com.winlator.star.store

import com.winlator.star.store.download.storeCoverUrls
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a game's page puts the controller's focus when it opens, and the cover's sources ([storeEntryTarget], [storeCoverUrls]). Plain JVM. */
class StoreFocusTest {

    @Test fun entry_isThePrimaryButtonWhileItCanBePressed() {
        assertEquals(StoreEntry.PRIMARY, storeEntryTarget(primaryActionable = true, hasGear = true))
        assertEquals(StoreEntry.PRIMARY, storeEntryTarget(primaryActionable = true, hasGear = false))
    }

    @Test fun entry_movesToTheGearWhenTheButtonOnlyShowsProgress() {
        assertEquals(StoreEntry.GEAR, storeEntryTarget(primaryActionable = false, hasGear = true))
    }

    @Test fun entry_fallsBackToTheBackButton() {
        assertEquals(StoreEntry.BACK, storeEntryTarget(primaryActionable = false, hasGear = false))
    }

    @Test fun coverUrls_keepOrderAndDropBlanksAndRepeats() {
        val urls = storeCoverUrls(listOf(null, "", "  ", "https://a/1.jpg", "https://b/2.jpg", " https://a/1.jpg "))
        assertEquals(listOf("https://a/1.jpg", "https://b/2.jpg"), urls)
    }

    @Test fun coverUrls_noSourcesMeansNoCover() {
        assertEquals(emptyList<String>(), storeCoverUrls(emptyList()))
        assertEquals(emptyList<String>(), storeCoverUrls(listOf(null, "")))
    }
}
