/*
 * Silverphase Android port — stale-image guard for asynchronously decoded artwork. GPLv3.
 *
 * C05 fix (stale film box art): the "当前胶片 / 当前相纸" card used
 * `produceState(initial, asset)`. produceState keeps ONE remembered state for the life of the
 * call site; its keys only restart the producer, and the initial value is read once. So after
 * picking another stock the old cover was still in `value`, the producer's `value == null` check
 * failed, and the card kept the previous film's box until it left composition (the card stays
 * composed under the material list, so returning from the list showed the old box).
 *
 * The fix tags every decoded image with the request it was decoded for and only shows it while
 * that request is still the current one. Pure logic, unit-tested in SpLoadedForTest.
 */
package com.spectrafilm.app.silverphase

/** A value produced for one request key. */
internal data class SpLoadedFor<K, V>(val key: K, val value: V)

/** The value only when it was produced for [key]; null for a different (stale) request. */
internal fun <K, V> SpLoadedFor<K, V>?.valueFor(key: K?): V? =
    if (this != null && key != null && this.key == key) value else null

/** The shipped WebP cover set is 384 px wide. */
private const val COVER_WIDTH_PX = 384

/** Decode at the smallest power-of-two sample that still covers the requested width. */
internal fun spCoverSampleSize(reqWidthPx: Int): Int {
    if (reqWidthPx <= 0) return 1
    var sample = 1
    while (COVER_WIDTH_PX / (sample * 2) >= reqWidthPx) sample *= 2
    return sample
}

/** Cache / request key of a cover asset at a requested width. */
internal fun spCoverKey(asset: String, reqWidthPx: Int): String = "$asset@${spCoverSampleSize(reqWidthPx)}"
