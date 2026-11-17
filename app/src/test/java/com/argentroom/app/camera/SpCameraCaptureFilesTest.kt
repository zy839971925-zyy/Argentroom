package com.argentroom.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class SpCameraCaptureFilesTest {
    @Test
    fun pendingRequestIsReadableAfterReopeningTheDirectory() = withCapture { capture ->
        capture.jpeg.writeBytes(byteArrayOf(1))
        capture.dng.writeBytes(byteArrayOf(2))
        capture.enqueue(request)
        val queued = capture.readQueued()
        assertEquals(request, queued)
        assertNull(capture.readInterrupted())
    }

    @Test
    fun startedRequestIsInterruptedRatherThanQueuedForAnotherPublish() = withCapture { capture ->
        capture.jpeg.writeBytes(byteArrayOf(1))
        capture.enqueue(request.copy(useDng = false))
        capture.start()
        assertNull(capture.readQueued())
        assertEquals(request.copy(useDng = false), capture.readInterrupted())
    }

    @Test
    fun finishedRawKeepsOnlyTheDngOriginal() = withCapture { capture ->
        capture.jpeg.writeBytes(byteArrayOf(1))
        capture.dng.writeBytes(byteArrayOf(2))
        capture.enqueue(request)
        capture.start()
        val original = capture.keepOriginal(useDng = true)
        assertEquals("dng", original.extension)
        assertEquals(2.toByte(), original.readBytes().single())
        assertEquals(false, capture.jpeg.exists())
        assertEquals(false, capture.dng.exists())
        assertNull(capture.readInterrupted())
    }

    @Test
    fun missingDngKeepsTheJpegEvenForAnInterruptedRawRequest() = withCapture { capture ->
        capture.jpeg.writeBytes(byteArrayOf(1))
        capture.enqueue(request)
        capture.start()
        val original = capture.keepOriginal(useDng = true)
        assertEquals("jpg", original.extension)
        assertEquals(1.toByte(), original.readBytes().single())
        assertEquals(original, capture.keepOriginal(useDng = true))
    }

    private fun withCapture(block: (SpCameraCaptureFiles) -> Unit) {
        val dir = Files.createTempDirectory("argentroom-camera-test").toFile()
        try {
            block(SpCameraCaptureFiles(dir, "Argentroom_20261115_214243_000").apply { prepare() })
        } finally {
            dir.deleteRecursively()
        }
    }

    private val request = SpCameraCaptureFiles.Request("kodak_portra_400", "MATRIX", -0.5f, true)
}
