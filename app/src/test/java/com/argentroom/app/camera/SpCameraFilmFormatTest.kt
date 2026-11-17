/*
 * Argentroom camera — film format of a camera shot (24 mm x capture aspect). GPLv3.
 */
package com.argentroom.app.camera

import com.argentroom.app.look.*
import com.argentroom.app.look.OperationalParamLimits
import com.argentroom.app.look.ParamsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpCameraFilmFormatTest {

    @Test
    fun shortSideIs24mmAtTheCaptureAspect() {
        assertEquals(32f, cameraFilmFormatMm(4f / 3f), 1e-4f)
        assertEquals(36f, cameraFilmFormatMm(3f / 2f), 1e-4f)
        assertEquals(24f * 16f / 9f, cameraFilmFormatMm(16f / 9f), 1e-4f)
        assertEquals(24f, cameraFilmFormatMm(1f), 1e-4f)
    }

    @Test
    fun orientationDoesNotMatter() {
        assertEquals(cameraFilmFormatMm(4000f / 3000f), cameraFilmFormatMm(3000f / 4000f), 1e-4f)
        assertEquals(cameraFilmFormatMm(16f / 9f), cameraFilmFormatMm(9f / 16f), 1e-4f)
    }

    @Test
    fun unusableAspectFallsBackTo43() {
        assertEquals(32f, cameraFilmFormatMm(0f), 1e-4f)
        assertEquals(32f, cameraFilmFormatMm(-2f), 1e-4f)
        assertEquals(32f, cameraFilmFormatMm(Float.NaN), 1e-4f)
        assertEquals(32f, cameraFilmFormatMm(Float.POSITIVE_INFINITY), 1e-4f)
    }

    @Test
    fun extremeAspectsStayInTheEngineRange() {
        val range = OperationalParamLimits.FILM_FORMAT_MM
        assertTrue(cameraFilmFormatMm(100f) in range)
        assertTrue(cameraFilmFormatMm(0.01f) in range)
        assertEquals(range.endInclusive, cameraFilmFormatMm(100f), 1e-4f)
    }

    @Test
    fun cameraEditSetsTheFormatOnTheState() {
        val state = ParamsState()
        assertEquals("precondition: the editor default is a 35 mm frame", 35f, state.filmFormatMm, 1e-4f)
        applyCameraFilmFormat(state, 4032f / 3024f)
        assertEquals(32f, state.filmFormatMm, 1e-4f)
        applyCameraFilmFormat(state, null)
        assertEquals("unknown aspect develops as 4:3", 32f, state.filmFormatMm, 1e-4f)
        applyCameraFilmFormat(state, 9f / 16f)
        assertEquals(24f * 16f / 9f, state.filmFormatMm, 1e-4f)
    }
}
