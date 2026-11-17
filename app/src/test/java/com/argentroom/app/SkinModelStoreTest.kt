/*
 * Argentroom for Android — unit tests for the bundled skin model store. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The pinned digest is the integrity gate: only the exact bundled bytes are ever kept, nothing
 * partial or wrong survives under the final name, and a verified file is reused without reading
 * the APK again. There is no network path at all (Argentroom C06 offline runtime).
 */
package com.argentroom.app

import com.argentroom.app.render.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinModelStoreTest {

    private val bytes = ByteArray(1000) { (it * 7).toByte() }
    private val dir: File = Files.createTempDirectory("skinmodel").toFile()
    private val pin = SkinModelStore.Pin(
        assetPath = "models/model.tflite",
        sourceUrl = "https://example.invalid/m/1/model.tflite",
        fileName = "model.tflite",
        sizeBytes = bytes.size.toLong(),
        sha256 = File.createTempFile("skinmodel", ".bin").let { f ->
            f.writeBytes(bytes)
            SkinModelStore.sha256(f).also { f.delete() }
        },
    )
    private val warnings = mutableListOf<String>()
    private var opened = 0

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun store(body: (() -> ByteArray)? = { bytes }) =
        SkinModelStore(dir, Dispatchers.IO, pin, { warnings += it }) {
            opened++
            body?.let { ByteArrayInputStream(it()) }
        }

    private fun leftovers() = dir.listFiles()?.map { it.name }.orEmpty()

    @Test
    fun unpacksVerifiesAndReusesWithoutReopening() = runBlocking {
        val s = store()
        assertNull(s.cached())
        assertNotNull(s.ensure())
        val file = s.ensure()!!
        assertTrue(bytes.contentEquals(file.readBytes()))
        assertEquals(1, opened)
        assertEquals(listOf("model.tflite"), leftovers())
        // A fresh store (next app start) verifies the file on disk instead of unpacking again.
        assertNotNull(store().cached())
        assertEquals(1, opened)
    }

    @Test
    fun wrongBytesAreNeverKept() = runBlocking {
        val tampered = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(store(body = { tampered }).ensure())
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(listOf("skin model digest mismatch"), warnings)
    }

    @Test
    fun wrongLengthIsRejected() = runBlocking {
        assertNull(store(body = { bytes + 1 }).ensure())
        assertNull(store(body = { bytes.copyOf(999) }).ensure())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun aMissingBundleFailsCleanly() = runBlocking {
        assertNull(store(body = null).ensure())
        assertEquals(listOf("skin model missing from the APK"), warnings)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun aDamagedFileOnDiskIsReplaced() = runBlocking {
        dir.mkdirs()
        File(dir, pin.fileName).writeBytes(ByteArray(bytes.size))
        val s = store()
        assertNull(s.cached())
        assertEquals(emptyList<String>(), leftovers())
        assertTrue(bytes.contentEquals(s.ensure()!!.readBytes()))
    }

    @Test
    fun clearRemovesTheModel() = runBlocking {
        val s = store()
        assertNotNull(s.ensure())
        s.clear()
        assertNull(s.cached())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun theProductionPinHasNoRuntimeUrl() {
        val p = SkinModelStore.Pin.SELFIE_MULTICLASS
        assertEquals("models/selfie_multiclass_256x256.tflite", p.assetPath)
        assertFalse(p.assetPath.startsWith("http"))
        assertEquals(16_371_837L, p.sizeBytes)
    }
}
