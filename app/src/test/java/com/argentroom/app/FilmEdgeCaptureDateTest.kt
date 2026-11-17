package com.argentroom.app

import com.argentroom.app.export.*
import com.argentroom.app.look.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilmEdgeCaptureDateTest {
    @Test fun parsesExifStamp() {
        assertEquals(listOf(2026, 11, 15), FilmEdgeCapture.exifDate("2026:11:15 17:54:01"))
        assertEquals(listOf(2024, 1, 2), FilmEdgeCapture.exifDate(" 2024-01-02T08:00"))
    }

    @Test fun rejectsUnsetStamp() {
        assertNull(FilmEdgeCapture.exifDate("0000:00:00 00:00:00"))
        assertNull(FilmEdgeCapture.exifDate("    :  :     :  :  "))
        assertNull(FilmEdgeCapture.exifDate(null))
    }

    @Test fun resolvedDateFollowsOrder() {
        val capture = FilmEdgeCapture(year = 2026, month = 11, day = 5, data = "1/125 F2.8")
        val edge = com.argentroom.engine.FilmEdgeParams(dateActive = true)
        assertEquals("'26 11 5", capture.resolve(edge, "x").renderDateText)
        assertEquals("11 5 '26", capture.resolve(edge.copy(dateOrder = "us"), "x").renderDateText)
    }

    @Test fun defaultExportNameCarriesTheNewBrand() {
        val name = exportBaseName("", 0L)
        assert(name.startsWith("Argentroom_")) { name }
    }
}
