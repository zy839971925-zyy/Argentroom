package com.argentroom.app.camera

import com.argentroom.app.camera.SpCameraCaptureResult.Input
import com.argentroom.app.camera.SpCameraCaptureResult.SavedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpCameraCaptureResultTest {
    @Test
    fun dualCaptureWaitsForBothFilesInEitherOrder() {
        for (first in SavedImage.entries) {
            val capture = SpCameraCaptureResult(expectsDng = true, useDng = true)
            val second = SavedImage.entries.single { it != first }
            assertNull(capture.saved(first))
            assertNull(capture.saved(first))
            assertEquals(Input.RAW, capture.saved(second))
            assertNull(capture.saved(second))
            assertNull(capture.error())
        }
    }

    @Test
    fun processingErrorDoesNotDiscardTheOtherFormatsLateJpeg() {
        val capture = SpCameraCaptureResult(expectsDng = true, useDng = true)
        assertNull(capture.error())
        assertEquals(Input.JPEG, capture.saved(SavedImage.JPEG))
        assertNull(capture.saved(SavedImage.DNG))
    }

    @Test
    fun missingDngUsesTheCompletedJpegAtTimeout() {
        val capture = SpCameraCaptureResult(expectsDng = true, useDng = true)
        assertNull(capture.saved(SavedImage.JPEG))
        assertEquals(Input.JPEG, capture.timeout())
    }

    @Test
    fun zoomedDualCaptureUsesJpegAndStillDrainsBothCallbacks() {
        val capture = SpCameraCaptureResult(expectsDng = true, useDng = false)
        assertNull(capture.saved(SavedImage.JPEG))
        assertEquals(Input.JPEG, capture.saved(SavedImage.DNG))
    }

    @Test
    fun jpegOnlyCaptureCompletesAsSoonAsItHasAJpeg() {
        val good = SpCameraCaptureResult(expectsDng = false, useDng = false)
        assertEquals(Input.JPEG, good.saved(SavedImage.JPEG))
        val bad = SpCameraCaptureResult(expectsDng = false, useDng = false)
        assertEquals(Input.FAILED, bad.error())
        assertNull(bad.saved(SavedImage.JPEG))
    }

    @Test
    fun callbackTimeoutWithoutAFinishedJpegFailsOnce() {
        val capture = SpCameraCaptureResult(expectsDng = true, useDng = true)
        assertNull(capture.saved(SavedImage.DNG))
        assertEquals(Input.FAILED, capture.timeout())
        assertNull(capture.saved(SavedImage.JPEG))
        assertNull(capture.timeout())
    }
}
