/*
 * Silverphase Android port — the stale-cover guard behind the "当前胶片" card fix (C05). GPLv3.
 */
package com.spectrafilm.app.silverphase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpLoadedForTest {

    @Test
    fun valueIsShownOnlyForTheRequestItWasLoadedFor() {
        val portra = spCoverKey("silverphase/covers/kodak_portra_400.webp", 288)
        val velvia = spCoverKey("silverphase/covers/fujifilm_velvia_100.webp", 288)
        val loaded = SpLoadedFor(portra, "portra-bitmap")
        assertEquals("portra-bitmap", loaded.valueFor(portra))
        // The bug: after switching film the old box art was still shown for the new film.
        assertNull(loaded.valueFor(velvia))
    }

    @Test
    fun noRequestOrNothingLoadedShowsNothing() {
        val loaded = SpLoadedFor("a@128", 1)
        assertNull(loaded.valueFor(null))
        val none: SpLoadedFor<String, Int>? = null
        assertNull(none.valueFor("a@128"))
    }

    @Test
    fun coverKeyTracksTheActualDecodeSample() {
        assertEquals(1, spCoverSampleSize(0))
        assertEquals(1, spCoverSampleSize(-5))
        assertEquals(1, spCoverSampleSize(288))
        assertEquals(2, spCoverSampleSize(192))
        assertEquals(4, spCoverSampleSize(96))
        assertEquals(spCoverKey("x.webp", 300), spCoverKey("x.webp", 260))
        assertEquals(spCoverKey("x.webp", 300), spCoverKey("x.webp", 600))
        assertNotEquals(spCoverKey("x.webp", 300), spCoverKey("y.webp", 300))
        assertNotEquals(spCoverKey("x.webp", 192), spCoverKey("x.webp", 193))
        assertNotEquals(spCoverKey("x.webp", 96), spCoverKey("x.webp", 97))
    }

    @Test
    fun portraPushStocksShareTheBaseBox() {
        // Not a stale case: the two push variants legitimately reuse the Portra 800 artwork.
        fun cover(id: String) = SpStock(id, id, false, false, false, false, null, null).coverAsset
        assertEquals(cover("kodak_portra_800"), cover("kodak_portra_800_push1"))
        assertNotEquals(cover("kodak_portra_800"), cover("kodak_portra_400"))
    }
}
