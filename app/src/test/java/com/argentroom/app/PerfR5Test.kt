/*
 * Argentroom for Android — perf r5 app-side contracts. GPLv3.
 *
 *  * the band-wise ColorGrade is bit-identical to the per-pixel absolute-access loop it replaced
 *    (and a coarse JVM timing of the two is printed, not asserted);
 *  * the idle pre-render's device-state gate;
 *  * the GPU-release gate reads the memory budget snapshot schema and fails open;
 *  * an artifact hashed while written agrees with one hashed from disk.
 */
package com.argentroom.app

import com.argentroom.app.export.*
import com.argentroom.app.look.*
import com.argentroom.app.render.*
import com.argentroom.engine.ColorSpace
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfR5Test {
    @get:Rule val temp = TemporaryFolder()

    /** The pre-r5 ColorGrade.applyInPlace loop, verbatim, as the reference. */
    private fun referenceGrade(
        data: ByteBuffer, w: Int, h: Int, cs: ColorSpace, cctfEncoded: Boolean,
        saturation: Float, vibrance: Float, gamutCompress: Float,
    ) {
        val chroma = ColorGrade.isActive(saturation, vibrance)
        val gamut = GamutCompress.isActive(gamutCompress)
        if (!chroma && !gamut) return
        val sat = saturation / 100f
        val vib = vibrance / 100f
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val rgb = FloatArray(3)
        val n = w * h
        var i = 0
        while (i < n) {
            val k = i * 3
            var rl = OutputCctf.decode(cs, f.get(k), cctfEncoded)
            var gl = OutputCctf.decode(cs, f.get(k + 1), cctfEncoded)
            var bl = OutputCctf.decode(cs, f.get(k + 2), cctfEncoded)
            if (gamut) {
                rgb[0] = rl; rgb[1] = gl; rgb[2] = bl
                GamutCompress.apply(rgb, gamutCompress)
                rl = rgb[0]; gl = rgb[1]; bl = rgb[2]
            }
            var r2 = rl; var g2 = gl; var b2 = bl
            if (chroma) {
                rgb[0] = rl; rgb[1] = gl; rgb[2] = bl
                Oklab.scaleChromaLinear(rgb, sat, vib)
                r2 = rgb[0]; g2 = rgb[1]; b2 = rgb[2]
            }
            f.put(k, OutputCctf.encode(cs, r2, cctfEncoded))
            f.put(k + 1, OutputCctf.encode(cs, g2, cctfEncoded))
            f.put(k + 2, OutputCctf.encode(cs, b2, cctfEncoded))
            i++
        }
    }

    private fun image(w: Int, h: Int, seed: Long): ByteBuffer {
        val b = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
        val f = b.asFloatBuffer()
        val rnd = java.util.Random(seed)
        for (i in 0 until w * h * 3) f.put(i, rnd.nextFloat() * 1.1f - 0.05f)
        return b
    }

    private fun bytesOf(b: ByteBuffer): ByteArray {
        val d = b.duplicate(); d.position(0)
        return ByteArray(d.remaining()).also { d.get(it) }
    }

    @Test fun `banded grade is bit-identical to the per-pixel loop`() {
        // 300x700 = 210k px: crosses three 64 Ki-px band boundaries plus a partial band.
        data class Case(val cs: ColorSpace, val cctf: Boolean, val sat: Float, val vib: Float, val gamut: Float)
        val cases = listOf(
            Case(ColorSpace.SRGB, true, 25f, -40f, 0f),
            Case(ColorSpace.SRGB, false, -100f, 0f, 0.6f),
            Case(ColorSpace.REC2020, true, 0f, 70f, 0.3f),
        )
        for ((idx, c) in cases.withIndex()) {
            val a = image(300, 700, idx.toLong())
            val b = image(300, 700, idx.toLong())
            referenceGrade(a, 300, 700, c.cs, c.cctf, c.sat, c.vib, c.gamut)
            ColorGrade.applyInPlace(b, 300, 700, c.cs, c.cctf, c.sat, c.vib, c.gamut)
            assertArrayEquals("case $idx", bytesOf(a), bytesOf(b))
        }
    }

    @Test fun `banded grade timing (informational)`() {
        val w = 2000; val h = 1500 // 3 MP
        val a = image(w, h, 9); val b = image(w, h, 9)
        repeat(2) { // warm-up
            referenceGrade(a, w, h, ColorSpace.SRGB, true, 20f, 20f, 0.2f)
            ColorGrade.applyInPlace(b, w, h, ColorSpace.SRGB, true, 20f, 20f, 0.2f)
        }
        val t0 = System.nanoTime()
        referenceGrade(a, w, h, ColorSpace.SRGB, true, 20f, 20f, 0.2f)
        val t1 = System.nanoTime()
        ColorGrade.applyInPlace(b, w, h, ColorSpace.SRGB, true, 20f, 20f, 0.2f)
        val t2 = System.nanoTime()
        println("PERF_R5 colorgrade 3MP per-pixel=${(t1 - t0) / 1_000_000} ms banded=${(t2 - t1) / 1_000_000} ms (host JVM, not a device number)")
        assertArrayEquals(bytesOf(a), bytesOf(b))
    }

    private fun conditions(
        powerSave: Boolean = false, thermal: Int = 0, headroom: Float = Float.NaN,
        battery: Int = 80, charging: Boolean = false,
    ) = PrerenderConditions(powerSave, thermal, headroom, battery, charging)

    @Test fun `prerender device gate`() {
        assertNull(conditions().refusal())
        assertNull(conditions(thermal = 1).refusal())
        assertNull(conditions(headroom = 0.5f).refusal())
        assertNull(conditions(battery = -1).refusal())
        assertNull(conditions(battery = 5, charging = true).refusal())
        assertTrue(conditions(powerSave = true).refusal() != null)
        assertTrue(conditions(thermal = 2).refusal() != null)
        assertTrue(conditions(headroom = 0.9f).refusal() != null)
        assertTrue(conditions(battery = 19).refusal() != null)
    }

    @Test fun `prerender peak constant has one source`() {
        assertEquals(EngineMemoryModel.FULL_RES_PEAK_BYTES_PER_PIXEL, RenderPayloads.PRERENDER_PEAK_BYTES_PER_PIXEL)
    }

    private fun snapshot(gpuHost: Long, gpuDevice: Long, schema: String = "spk.memory_budget.v1") =
        """{"schema":"$schema","limit_bytes":100,"total":{"current_bytes":5,"peak_bytes":9,"denial_count":0,"denied_bytes":0},
        "domains":[{"id":0,"name":"unknown","current_bytes":5,"peak_bytes":0,"denial_count":0,"denied_bytes":0},
        {"id":5,"name":"gpu_host","current_bytes":$gpuHost,"peak_bytes":0,"denial_count":0,"denied_bytes":0},
        {"id":6,"name":"gpu_device","current_bytes":$gpuDevice,"peak_bytes":0,"denial_count":0,"denied_bytes":0}],"stages":[]}"""

    @Test fun `gpu release gate`() {
        assertFalse(gpuReleaseWorthwhile(snapshot(0, 0)))
        assertTrue(gpuReleaseWorthwhile(snapshot(1, 0)))
        assertTrue(gpuReleaseWorthwhile(snapshot(0, 7)))
        // Any doubt releases, as before.
        assertTrue(gpuReleaseWorthwhile(null))
        assertTrue(gpuReleaseWorthwhile("{ nope"))
        assertTrue(gpuReleaseWorthwhile(snapshot(0, 0, schema = "spk.memory_budget.v2")))
    }

    @Test fun `hashed-while-written artifact equals the disk hash`() {
        val f = temp.newFile("stage.part")
        val bytes = ByteArray(300_000) { (it * 13).toByte() }
        f.writeBytes(bytes)
        val sha = ExportCache.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val streamed = EncodedArtifact.fromHashedStage(f, bytes.size.toLong(), sha)
        val measured = EncodedArtifact.fromCompletedFile(f)
        assertEquals(measured, streamed)
        val wrongLength = runCatching { EncodedArtifact.fromHashedStage(f, 5L, sha) }
        assertTrue(wrongLength.isFailure)
        val gone = runCatching { EncodedArtifact.fromHashedStage(File(temp.root, "missing"), 5L, sha) }
        assertTrue(gone.isFailure)
    }
}
