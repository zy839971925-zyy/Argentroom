/*
 * Argentroom for Android — honest, stage-weighted export progress (#466). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.argentroom.app.export

import com.argentroom.app.editor.*

/**
 * The export's known stages, in order, each weighted by its share of a measured RELEASE
 * export (docs/research/perf-lab.md §19.1, 24 MP RAW on the test phone: total 6251 ms,
 * simulate 5504, decode 546, grade 30, the rest ~171 of which encode is most).
 *
 * Progress moves when a stage STARTS, to the summed weight of the stages before it. C05: the
 * engine now also publishes how far its render has got (stage- and band-weighted,
 * SpektraEngine.exportProgress), so SIMULATE -- ~85% of the time, minutes on a large frame,
 * where the bar used to sit at ~11% and look frozen -- fills in as the engine works
 * ([ExportProgressModel]). The bar never moves backwards.
 */
internal enum class ExportStage(val weight: Float) {
    SETUP(0.01f),
    DECODE(0.09f),
    EXIF(0.005f),
    SIMULATE(0.855f),
    GRADE(0.005f),
    ENCODE(0.035f),
    ;

    /** Fraction of the whole export done when this stage begins. */
    val startFraction: Float
        get() = entries.takeWhile { it != this }.sumOf { it.weight.toDouble() }.toFloat()
}

/** Monotonic merge: a late or repeated stage report can never pull progress back. */
internal fun mergeExportProgress(current: Float, stage: ExportStage): Float =
    maxOf(current, stage.startFraction).coerceIn(0f, 1f)

/**
 * C05: the export runtime's progress, one photo or a batch of [total] photos. Pure (clock and
 * engine readings are passed in) so it is unit-tested; [ExportWorkRuntime] owns one.
 *
 * Inside SIMULATE the engine's fraction is used as published, plus a bounded forward creep at
 * the rate the engine has been going, so a long stage between two engine updates (a whole
 * scan pass on a 20 MP frame) still shows the work moving. The creep never runs more than
 * [CREEP_LEAD] ahead of what the engine has confirmed and stops short of the stage's end.
 */
internal class ExportProgressModel {
    private var index = 0
    private var total = 1
    private var stage: ExportStage? = null
    private var photo = 0f
    private var simConfirmed = 0f
    private var simStartMs = 0L
    private var confirmedAtMs = 0L
    private var encodeStartMs = 0L
    private var overall = 0f

    val value: Float get() = overall

    fun reset() {
        index = 0; total = 1; stage = null; photo = 0f
        simConfirmed = 0f; simStartMs = 0L; confirmedAtMs = 0L; overall = 0f
    }

    /** A batch moves to photo [index] (0-based) of [total]. */
    fun photo(index: Int, total: Int) {
        val t = total.coerceAtLeast(1)
        val i = index.coerceIn(0, t - 1)
        if (t == this.total && i == this.index && stage != null) return
        this.total = t
        this.index = i
        stage = null
        photo = 0f
        simConfirmed = 0f
        publish()
    }

    fun stage(next: ExportStage, nowMs: Long) {
        // A photo's stages only move forward; a repeat or late report changes nothing.
        val cur = stage
        if (cur != null && next.ordinal <= cur.ordinal) return
        stage = next
        photo = maxOf(photo, next.startFraction)
        if (next == ExportStage.SIMULATE) {
            simConfirmed = 0f
            simStartMs = nowMs
            confirmedAtMs = nowMs
        }
        if (next == ExportStage.ENCODE) encodeStartMs = nowMs
        publish()
    }

    /**
     * 1.0.1: ENCODE has no engine readings (a 50 MP JPEG/Ultra HDR compress and the gallery
     * write take seconds), so the bar eases toward [ENCODE_CAP] of the stage on a clock instead
     * of sitting still at the stage's start. It never reaches the end; only completion does.
     */
    fun encodeTick(nowMs: Long) {
        if (stage != ExportStage.ENCODE) return
        val t = (nowMs - encodeStartMs).coerceAtLeast(0L).toDouble()
        val eased = (1.0 - kotlin.math.exp(-t / ENCODE_TAU_MS)).toFloat() * ENCODE_CAP
        val s = ExportStage.ENCODE
        photo = maxOf(photo, s.startFraction + s.weight * eased)
        publish()
    }

    /** An engine reading taken while SIMULATE runs (0..1 of the render). */
    fun engine(fraction: Float, nowMs: Long) {
        if (stage != ExportStage.SIMULATE) return
        val f = fraction.coerceIn(0f, 1f)
        if (f > simConfirmed) {
            simConfirmed = f
            confirmedAtMs = nowMs
        }
        val elapsed = (confirmedAtMs - simStartMs).coerceAtLeast(1L)
        val rate = simConfirmed / elapsed // per ms, as the engine has gone so far
        val creep = if (simConfirmed > 0f) {
            (rate * (nowMs - confirmedAtMs)).coerceIn(0f, CREEP_LEAD)
        } else {
            // Nothing confirmed yet (setup, LUT bakes): a slow, small lead-in.
            ((nowMs - simStartMs) / LEAD_IN_MS.toFloat()).coerceIn(0f, 1f) * CREEP_LEAD * 0.5f
        }
        val sim = minOf(simConfirmed + creep, SIM_CAP).coerceAtLeast(simConfirmed.coerceAtMost(1f))
        val s = ExportStage.SIMULATE
        photo = maxOf(photo, s.startFraction + s.weight * sim)
        publish()
    }

    private fun publish() {
        val v = ((index + photo.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f)
        if (v > overall) overall = v
    }

    companion object {
        const val CREEP_LEAD = 0.04f
        const val SIM_CAP = 0.995f
        const val LEAD_IN_MS = 20_000L
        const val ENCODE_CAP = 0.9f
        const val ENCODE_TAU_MS = 4_000.0
    }
}
