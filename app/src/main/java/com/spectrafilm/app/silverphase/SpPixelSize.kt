/*
 * Silverphase Android port — the status overlay's original pixel size. GPLv3.
 * Reads the stored dimensions without decoding pixels: BitmapFactory bounds for raster
 * formats, EXIF dimension tags for RAW/DNG. Returns null rather than guessing.
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.editor.*
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

internal fun spReadPixelSize(ctx: Context, uri: Uri): String? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var w = opts.outWidth; var h = opts.outHeight
    var rotated = false
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        val exif = ExifInterface(input)
        if (w <= 0 || h <= 0) {
            w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
            h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
        }
        rotated = exif.rotationDegrees % 180 != 0
    }
    if (w <= 0 || h <= 0) null else {
        val (a, b) = if (rotated) h to w else w to h
        "$a × $b"
    }
}.getOrNull()
