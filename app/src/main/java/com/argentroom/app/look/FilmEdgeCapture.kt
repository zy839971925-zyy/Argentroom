// Capture-side text port of Silverphase MobileCaptureText. SPDX-License-Identifier: GPL-3.0-or-later
package com.argentroom.app.look

import com.argentroom.app.editor.*
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.argentroom.app.camera.SpCameraStorage
import com.argentroom.engine.FilmEdgeParams
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class FilmEdgeCapture(
    val year: Int? = null,
    val month: Int = 0,
    val day: Int = 0,
    val data: String = "",
    val aperture: Float = 0f,
    val stockNames: Map<String, String> = emptyMap(),
    val legacyDate: String? = null,
) {
    fun resolve(edge: FilmEdgeParams, stock: String): FilmEdgeParams {
        if (!edge.spatial) return FilmEdgeParams()
        val yy = year?.let { String.format(Locale.ROOT, "'%02d", it % 100) }
        val date = yy?.let { if (edge.dateOrder == "us") "$month $day $it" else "$it $month $day" }.orEmpty()
        return edge.copy(
            captureDate = date,
            captureData = data,
            aperture = aperture,
            edgeText = stockNames[stock].orEmpty(),
        )
    }

    companion object {
        fun read(ctx: Context, uri: Uri): FilmEdgeCapture {
            var meta = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { fromExif(ExifInterface(it)) }
            }.getOrNull() ?: FilmEdgeCapture()
            if (meta.year == null || SpCameraStorage.isDng(ctx, uri)) {
                val shutter = SpCameraStorage.shutterDate(ctx, uri)
                exifDate(shutter)?.let { (y, m, d) ->
                    meta = meta.copy(year = y, month = m, day = d, legacyDate = DateStampText.fromExif(shutter))
                }
            }
            if (meta.year == null) {
                // No EXIF capture date (a picker copy that dropped EXIF, a screenshot): the
                // MediaStore DATE_TAKEN the platform recorded is still the shooting time, not
                // a fabricated one. Never falls back to "today".
                mediaStoreDateTaken(ctx, uri)?.let { meta = meta.withDate(it) }
            }
            return meta.copy(stockNames = StockCatalog.stocks(ctx).mapValues { it.value.displayName })
        }

        /** Parse an EXIF `yyyy:MM:dd HH:mm:ss` stamp into year, month, day; null when invalid. */
        internal fun exifDate(stamp: String?): List<Int>? =
            stamp?.trim()?.take(10)?.split(':', '-')?.mapNotNull(String::toIntOrNull)
                ?.takeIf { it.size == 3 && it[0] in 1..9999 && it[1] in 1..12 && it[2] in 1..31 }

        private fun mediaStoreDateTaken(ctx: Context, uri: Uri): Long? = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media.DATE_TAKEN), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null }
        }.getOrNull()

        private fun FilmEdgeCapture.withDate(ms: Long): FilmEdgeCapture {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            val y = cal.get(java.util.Calendar.YEAR)
            val m = cal.get(java.util.Calendar.MONTH) + 1
            val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            return copy(year = y, month = m, day = d, legacyDate = legacyDate ?: DateStampText.of(cal))
        }

        internal fun fromExif(exif: ExifInterface): FilmEdgeCapture {
            // DateTimeOriginal first (Silverphase reads only it); a file that lacks it but
            // carries DateTimeDigitized or DateTime still has a real shooting date.
            val stamp = listOf(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_DATETIME,
            ).firstNotNullOfOrNull { tag -> exif.getAttribute(tag)?.takeIf { exifDate(it) != null } }
            val date = exifDate(stamp)
            fun number(tag: String): Double? =
                exif.getAttributeDouble(tag, Double.NaN).takeIf(Double::isFinite)
            fun one(v: Double): String = String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")

            val aperture = number(ExifInterface.TAG_F_NUMBER)?.takeIf { it in 0.1..256.0 } ?: 0.0
            val parts = mutableListOf<String>()
            number(ExifInterface.TAG_EXPOSURE_TIME)?.takeIf { it in 0.0000001..1_000_000.0 }?.let {
                parts += if (it >= 1.0) one(it) + "s" else "1/" + (1 / it).roundToInt()
            }
            if (aperture > 0) parts += "F" + one(aperture)
            val mode = exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, 0)
            val programs = mapOf(1 to "M", 2 to "P", 3 to "A", 4 to "S")
            programs[mode]?.let { parts += it }
            number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.takeIf { abs(it) in 0.05..99.0 }?.let {
                parts += String.format(Locale.ROOT, "%+.1f", it)
            }
            number(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it in 1.0..4000.0 }?.let {
                parts += "${it.roundToInt()}mm"
            }
            while (parts.joinToString(" ").length > 22) parts.removeAt(parts.lastIndex)
            return FilmEdgeCapture(date?.get(0), date?.get(1) ?: 0, date?.get(2) ?: 0,
                parts.joinToString(" "), aperture.toFloat(),
                legacyDate = DateStampText.fromExif(stamp),
            )
        }
    }
}
