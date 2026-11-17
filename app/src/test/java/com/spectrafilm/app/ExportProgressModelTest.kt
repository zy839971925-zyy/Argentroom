/*
 * Spektrafilm for Android — engine-driven export progress (C05). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import com.spectrafilm.app.editor.*
import com.spectrafilm.app.export.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProgressModelTest {

    @Test
    fun simulateFillsInFromTheEngineInsteadOfStallingAtItsStart() {
        val m = ExportProgressModel()
        m.stage(ExportStage.SETUP, 0)
        m.stage(ExportStage.DECODE, 0)
        m.stage(ExportStage.EXIF, 100)
        m.stage(ExportStage.SIMULATE, 200)
        val atStart = m.value
        // The old bar stopped here (~10.5%) for the whole render.
        assertEquals(ExportStage.SIMULATE.startFraction, atStart, 1e-6f)
        m.engine(0.5f, 60_200)
        val half = ExportStage.SIMULATE.startFraction + ExportStage.SIMULATE.weight * 0.5f
        assertEquals(half, m.value, 1e-4f)
        assertTrue(m.value > 0.5f)
    }

    @Test
    fun creepKeepsMovingBetweenEngineUpdatesButNeverFarAhead() {
        val m = ExportProgressModel()
        m.stage(ExportStage.SIMULATE, 0)
        m.engine(0.2f, 10_000)
        val confirmed = m.value
        m.engine(0.2f, 15_000) // no new engine reading for 5 s
        val crept = m.value
        assertTrue("bar must keep moving", crept > confirmed)
        m.engine(0.2f, 1_000_000) // a very long stall
        val cap = ExportStage.SIMULATE.startFraction +
            ExportStage.SIMULATE.weight * (0.2f + ExportProgressModel.CREEP_LEAD)
        assertTrue(m.value <= cap + 1e-5f)
    }

    @Test
    fun neverMovesBackwards() {
        val m = ExportProgressModel()
        m.stage(ExportStage.SIMULATE, 0)
        m.engine(0.6f, 1_000)
        val v = m.value
        m.engine(0.1f, 2_000) // a stale or lower reading
        assertTrue(m.value >= v)
        m.stage(ExportStage.DECODE, 3_000) // a late, earlier stage
        assertTrue(m.value >= v)
    }

    @Test
    fun batchPhotosShareTheBar() {
        val m = ExportProgressModel()
        m.photo(0, 4)
        m.stage(ExportStage.SIMULATE, 0)
        m.engine(1f, 1_000)
        m.stage(ExportStage.ENCODE, 1_000)
        val first = m.value
        assertTrue(first in 0.2f..0.25f)
        m.photo(1, 4)
        assertTrue(m.value >= first)
        m.stage(ExportStage.SETUP, 1_100)
        m.stage(ExportStage.SIMULATE, 1_200)
        m.engine(0.5f, 2_000)
        assertTrue(m.value > 0.25f && m.value < 0.5f)
    }

    @Test
    fun engineReadingsOutsideSimulateAreIgnored() {
        val m = ExportProgressModel()
        m.stage(ExportStage.DECODE, 0)
        m.engine(1f, 100)
        assertEquals(ExportStage.DECODE.startFraction, m.value, 1e-6f)
    }
}
