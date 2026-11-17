/*
 * Argentroom (银间) — Silverphase scene latitude for the Android engine. GPLv3.
 *
 * Port of Silverphase v1.2.5's RFC-023 measurement half, the part the 拍摄 panel's graph shows:
 *  - engine/src/core/latitude_fit.cpp  read_boundaries(), scene_stats(), placed_histogram()
 *  - engine/src/pipeline/engine.cpp     medium_probe_params(), probe_medium()
 *  - engine/src/pipeline/scene_latitude.cpp  scene_latitude_sample()
 *  - ios/.../MobileEngine.swift         MobileLatitude (below / within / above, separation)
 *
 * What is measured, and on what:
 *  - The MEDIUM is a neutral ramp of 1024 x 8 pixels, 0.184 * 2^E for E in [-12, +12], rendered
 *    through the real Android engine (SpektraEngine.simulate, film -> print -> scan) with the
 *    session's film, paper, enlarger, scanner and Exp. Comp., and with auto-exposure, grain,
 *    halation, glare, diffusion, lens blur, scene latitude, the date imprint, crop, the tone
 *    curve and both CCTFs switched off, as Silverphase's medium_probe_params() does. The print's
 *    relative luminance Y along the ramp gives the ISO 6846-style boundaries: highlight where Y
 *    reaches 90 % of the way from black to white, shadow 0.04 density above black.
 *  - The SCENE is the whole decoded source proxy (the same linear ProPhoto buffer the preview
 *    renders from, never cropped), scaled by the engine's own auto-exposure gain
 *    (spk_meter_exposure_ev), reduced with Silverphase's default "power" norm and placed on the
 *    stop axis relative to the metered mid-grey 0.184, in 128 quarter-stop bins over -16..+16.
 *  - Exp. Comp. is applied in the film's expose stage, after the meter, so like Silverphase it
 *    moves the medium's boundaries (re-probed) and not the scene statistic.
 * Nothing here is an RGB histogram of the rendered photograph.
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.editor.*
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/** One measurement, in MobileLatitude's shape. Bins are fractions of the frame. */
internal data class SpLatitude(
    val bins: DoubleArray,
    val loEV: Double,
    val hiEV: Double,
    /** Medium boundaries on the scene's stop axis. */
    val shadow: Double,
    val highlight: Double,
    val rampEV: DoubleArray,
    val rampY: DoubleArray,
    val yWhite: Double,
    val samples: Int,
    /** Engine auto-exposure EV the scene was placed with (0 with the meter off). */
    val meterEv: Double,
    /** The Android scene-latitude curve was engaged and the bins are the placed histogram. */
    val placed: Boolean,
) {
    val centres: DoubleArray by lazy {
        DoubleArray(bins.size) { loEV + (it + 0.5) * (hiEV - loEV) / maxOf(1, bins.size) }
    }
    val below: Double get() = centres.indices.filter { centres[it] < shadow }.sumOf { bins[it] }
    val above: Double get() = centres.indices.filter { centres[it] > highlight }.sumOf { bins[it] }
    val within: Double get() = maxOf(0.0, bins.sum() - below - above)
    val stopsHeld: Double get() = highlight - shadow

    /** Tonal separation of the print along the ramp, normalised to its peak (MobileLatitude.separation). */
    val separation: List<Pair<Double, Double>> by lazy {
        fun lightness(y: Double): Double {
            val t = maxOf(y, 0.0) / maxOf(yWhite, 1e-9)
            return if (t > 0.008856) 116 * t.pow(1.0 / 3.0) - 16 else 903.3 * t
        }
        val n = minOf(rampY.size, rampEV.size)
        if (n < 2) return@lazy emptyList()
        val slopes = (0 until n - 1).map { i ->
            (rampEV[i] + rampEV[i + 1]) / 2 to
                maxOf(0.0, (lightness(rampY[i + 1]) - lightness(rampY[i])) / maxOf(1e-9, rampEV[i + 1] - rampEV[i]))
        }
        val peak = maxOf(slopes.maxOf { it.second }, 1e-9)
        slopes.map { it.first to it.second / peak }
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** The ramp's medium, as read_boundaries() leaves it. */
internal class SpMedium(
    val ev: DoubleArray,
    val y: DoubleArray,
    val yBlack: Double,
    val yWhite: Double,
    val shadowEv: Double,
    val highlightEv: Double,
)

internal class SpLatitudeException(message: String) : Exception(message)

internal object SpLatitudeMeter {
    const val MIDGRAY = 0.184
    private const val RAMP_SAMPLES = 1024
    private const val RAMP_ROWS = 8
    private const val RAMP_LO = -12.0
    private const val RAMP_HI = 12.0
    private const val DOMAIN_STOPS = 24.0
    const val BINS = 128
    const val LO = -16.0
    const val HI = 16.0
    private const val MAX_SCENE_SAMPLES = 1 shl 16

    // The medium only depends on its probe params, so the last one is kept (engine.cpp keeps
    // it on the session the same way: a crop or a grain toggle does not re-probe).
    private var mediumKey: SpektraParams? = null
    private var medium: SpMedium? = null

    /** Silverphase medium_probe_params(), on the Android parameter surface. */
    fun probeParams(p: SpektraParams): SpektraParams = p.copy(
        camera = p.camera.copy(
            autoExposure = false,
            lensBlurUm = 0f,
            diffusionFilter = p.camera.diffusionFilter.copy(active = false),
            sceneLatitude = p.camera.sceneLatitude.copy(active = false),
            dateStamp = p.camera.dateStamp.copy(active = false),
            filmEdge = com.spectrafilm.engine.FilmEdgeParams(),
        ),
        filmRender = p.filmRender.copy(
            grain = p.filmRender.grain.copy(active = false, sublayersActive = false),
            halation = p.filmRender.halation.copy(active = false),
            glare = p.filmRender.glare.copy(active = false),
        ),
        printRender = p.printRender.copy(
            glare = p.printRender.glare.copy(active = false),
            contrastMask = p.printRender.contrastMask.copy(active = false),
        ),
        enlarger = p.enlarger.copy(lensBlur = 0f, diffusionFilter = p.enlarger.diffusionFilter.copy(active = false)),
        scanner = p.scanner.copy(lensBlur = 0f, unsharpMask = 0f to 0f),
        io = p.io.copy(
            inputCctfDecoding = false,
            // Linear sRGB out, so Y is the Rec. 709 row; CAT02-adapted luminance in iOS.
            outputColorSpace = ColorSpace.LINEAR_SRGB,
            outputCctfEncoding = false,
            crop = false,
            upscaleFactor = 1f,
        ),
        toneCurve = p.toneCurve.copy(active = false),
    )

    @Synchronized
    fun medium(engine: SpektraEngine, params: SpektraParams): SpMedium {
        val probe = probeParams(params)
        val cached = medium
        if (cached != null && mediumKey == probe) return cached
        val m = probeMedium(engine, probe)
        mediumKey = probe; medium = m
        return m
    }

    private fun probeMedium(engine: SpektraEngine, probe: SpektraParams): SpMedium {
        val ev = DoubleArray(RAMP_SAMPLES) { RAMP_LO + (RAMP_HI - RAMP_LO) * it / (RAMP_SAMPLES - 1) }
        val buf = ByteBuffer.allocateDirect(RAMP_SAMPLES * RAMP_ROWS * 3 * 4).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (row in 0 until RAMP_ROWS) for (j in 0 until RAMP_SAMPLES) {
            val v = (MIDGRAY * 2.0.pow(ev[j])).toFloat()
            fb.put(v); fb.put(v); fb.put(v)
        }
        val image = LinearImage.fromDataLease(buf, RAMP_SAMPLES, RAMP_ROWS, lease = AutoCloseable { })
        val y = DoubleArray(RAMP_SAMPLES)
        image.use { img ->
            engine.simulate(img, probe).use { result ->
                result.acquireDataLease().use { lease ->
                    val out = lease.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
                    val w = result.width; val h = result.height
                    if (w != RAMP_SAMPLES || h < 1) throw SpLatitudeException("medium probe returned ${w}x$h")
                    for (i in 0 until h) for (j in 0 until w) {
                        val o = (i * w + j) * 3
                        y[j] += (0.2126 * out.get(o) + 0.7152 * out.get(o + 1) + 0.0722 * out.get(o + 2)) / h
                    }
                }
            }
        }
        return readBoundaries(ev, y)
    }

    /** latitude_fit.cpp read_boundaries(). */
    fun readBoundaries(ev: DoubleArray, y: DoubleArray): SpMedium {
        val n = ev.size
        if (n < 2 || y.size != n) throw SpLatitudeException("the medium probe returned no ramp")
        val black = y.min(); val white = y.max()
        if (!(black > 0.0) || !(white > black)) throw SpLatitudeException("the medium probe's print has no tonal range")
        val tHi = black + 0.90 * (white - black)
        val tLo = black * 10.0.pow(0.04)
        fun cross(target: Double): Double? {
            for (i in 0 until n - 1) {
                val a = y[i]; val b = y[i + 1]
                if ((a - target) * (b - target) <= 0.0 && b != a) return ev[i] + (target - a) / (b - a) * (ev[i + 1] - ev[i])
            }
            return null
        }
        val s = cross(tLo); val hgh = cross(tHi)
        if (s == null || hgh == null || !(s < hgh)) throw SpLatitudeException("the medium's boundaries do not lie inside the probe's range")
        return SpMedium(ev, y, black, white, s, hgh)
    }

    /** scene_latitude_sample() + scene_stats(): the stop of each pixel, binned. */
    fun sceneHistogram(rgb: java.nio.FloatBuffer, pixels: Int, gain: Double): Pair<DoubleArray, Int> {
        val bins = DoubleArray(BINS)
        val step = maxOf(1, pixels / MAX_SCENE_SAMPLES)
        val width = (HI - LO) / BINS
        var count = 0
        var i = 0
        while (i < pixels) {
            val o = i * 3
            val r = maxOf(rgb.get(o) * gain, 0.0); val g = maxOf(rgb.get(o + 1) * gain, 0.0); val b = maxOf(rgb.get(o + 2) * gain, 0.0)
            val s2 = r * r + g * g + b * b
            val v = if (s2 > 0.0) (r * r * r + g * g * g + b * b * b) / s2 else 0.0
            if (v > 0.0 && v.isFinite()) {
                val e = log2(v / MIDGRAY).coerceIn(-DOMAIN_STOPS, DOMAIN_STOPS)
                bins[floor((e - LO) / width).toInt().coerceIn(0, BINS - 1)] += 1.0
                count++
            }
            i += step
        }
        if (count > 0) for (k in bins.indices) bins[k] /= count.toDouble()
        return bins to count
    }

    /** placed_histogram() through the Android engine's scene-latitude map (model/scene_latitude.cpp). */
    fun placed(hist: DoubleArray, sl: com.spectrafilm.engine.SceneLatitudeParams): DoubleArray {
        val hiOn = sl.highlightRoom > 0f; val loOn = sl.shadowRoom > 0f
        if (!sl.active || (!hiOn && !loOn)) return hist
        val m = sl.rolloff.toDouble().coerceIn(1.0, 4.0)
        fun compress(d: Double, h: Double): Double =
            if (!(d > 0.0)) d else when (m) {
                2.0 -> d * h / sqrt(h * h + d * d)
                1.0 -> d * h / (h + d)
                else -> d * h / (h.pow(m) + d.pow(m)).pow(1.0 / m)
            }
        fun map(e: Double): Double {
            var f = when {
                hiOn && e > sl.highlightKnee -> sl.highlightKnee + compress(e - sl.highlightKnee, sl.highlightRoom.toDouble())
                loOn && e < sl.shadowKnee -> sl.shadowKnee - compress(sl.shadowKnee - e, sl.shadowRoom.toDouble())
                else -> e
            }
            var d = f - e
            if (d > 0.0) { val l = if (sl.maxLift > 0f) sl.maxLift.toDouble() else 4.0; d = d * l / sqrt(l * l + d * d) }
            f = e + d
            return f
        }
        val sub = 8
        val width = (HI - LO) / BINS
        val out = DoubleArray(BINS)
        for (b in hist.indices) {
            val f = hist[b]; if (f == 0.0) continue
            for (k in 0 until sub) {
                val e = LO + (b + (k + 0.5) / sub) * width
                out[floor((map(e) - LO) / width).toInt().coerceIn(0, BINS - 1)] += f / sub
            }
        }
        return out
    }

    /** Whole measurement on a source proxy; heavy (one engine render of the ramp when the medium changed). */
    fun measure(engine: SpektraEngine, source: LinearImage, params: SpektraParams): SpLatitude {
        val meterEv = engine.meterExposureEv(source, params)
        val gain = (2.0.pow(meterEv)).toFloat().toDouble()   // narrowed to float32 like the node
        val (hist, count) = source.acquireDataLease().use { lease ->
            val fb = lease.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
            sceneHistogram(fb, source.width * source.height, gain)
        }
        if (count == 0) throw SpLatitudeException("the frame has no positive pixels")
        val med = medium(engine, params)
        val sl = params.camera.sceneLatitude
        val placed = placed(hist, sl)
        // 128 points are enough to draw (engine.cpp keeps every 8th ramp sample).
        val idx = med.ev.indices.step(8).toList()
        return SpLatitude(
            bins = placed, loEV = LO, hiEV = HI, shadow = med.shadowEv, highlight = med.highlightEv,
            rampEV = DoubleArray(idx.size) { med.ev[idx[it]] }, rampY = DoubleArray(idx.size) { med.y[idx[it]] },
            yWhite = med.yWhite, samples = count, meterEv = meterEv, placed = placed !== hist,
        )
    }
}
