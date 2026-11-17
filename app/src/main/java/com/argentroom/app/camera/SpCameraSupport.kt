/*
 * Argentroom camera — preferences, orientation and formatting helpers. GPLv3.
 * Film modeling powered by spektrafilm.
 * Split out of SpCameraScreen.kt; see that file for the screen's design notes.
 */
package com.argentroom.app.camera

import com.argentroom.app.*
import com.argentroom.app.diagnostics.*
import com.argentroom.app.editor.*
import com.argentroom.app.export.*
import com.argentroom.app.settings.*
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.argentroom.app.silverphase.SpStock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

internal class SpCameraPrefs(context: Context) {
    private val p = context.getSharedPreferences("silverphase_camera", Context.MODE_PRIVATE)
    var film: String?
        get() = p.getString("film", null)
        set(v) = p.edit { putString("film", v) }
    var flash: Int
        get() = p.getInt("flash", ImageCapture.FLASH_MODE_OFF).takeIf {
            it == ImageCapture.FLASH_MODE_OFF || it == ImageCapture.FLASH_MODE_AUTO || it == ImageCapture.FLASH_MODE_ON
        } ?: ImageCapture.FLASH_MODE_OFF
        set(v) = p.edit { putInt("flash", v) }
    var grid: GridMode
        get() = GridMode.entries.firstOrNull { it.name == p.getString("grid", null) } ?: GridMode.OFF
        set(v) = p.edit { putString("grid", v.name) }
    var level: Boolean
        get() = p.getBoolean("level", false)
        set(v) = p.edit { putBoolean("level", v) }
    var metering: SpCameraMetering
        get() = SpCameraMetering.parse(p.getString("metering", null))
        set(v) = p.edit { putString("metering", v.name) }
    var front: Boolean
        get() = p.getBoolean("front", false)
        set(v) = p.edit { putBoolean("front", v) }
    /** Silverphase "Prefer RAW capture" (MobileCameraRecipe.defaultPrefersRAW): on by default. */
    var prefersRaw: Boolean
        get() = p.getBoolean("prefers_raw", true)
        set(v) = p.edit { putBoolean("prefers_raw", v) }
    var askedPermission: Boolean
        get() = p.getBoolean("asked_permission", false)
        set(v) = p.edit { putBoolean("asked_permission", v) }
}

internal const val CAPTURE_CALLBACK_TIMEOUT_MS = 15_000L

/** Saved film, else the user's default film, else Portra 400 (Silverphase's default), else the first. */
internal fun initialFilm(prefs: SpCameraPrefs, defaultFilm: String, films: List<SpStock>): String {
    val ids = films.mapTo(HashSet()) { it.id }
    return prefs.film?.takeIf { it in ids }
        ?: defaultFilm.takeIf { it in ids }
        ?: "kodak_portra_400".takeIf { it in ids }
        ?: films.firstOrNull()?.id
        ?: ""
}

internal fun nextFlash(mode: Int): Int = when (mode) {
    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
    else -> ImageCapture.FLASH_MODE_OFF
}

internal fun quarterDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

/**
 * OrientationEventListener degrees -> Surface rotation for the capture, with ~10 degrees of
 * hysteresis around each diagonal so the controls do not flicker when the phone is held at 45.
 */
internal fun rotationForOrientation(degrees: Int, current: Int): Int {
    val target = when (degrees) {
        in 45 until 135 -> Surface.ROTATION_270
        in 135 until 225 -> Surface.ROTATION_180
        in 225 until 315 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
    if (target == current) return current
    val centre = when (target) {
        Surface.ROTATION_270 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_90 -> 270
        else -> 0
    }
    val distance = abs(Math.IEEEremainder((degrees - centre).toDouble(), 360.0))
    return if (distance <= 35.0) target else current
}

internal fun formatEv(ev: Float): String {
    val sign = if (ev > 0f) "+" else if (ev < 0f) "\u2212" else ""
    return sign + String.format(Locale.ROOT, "%.1f", abs(ev))
}

internal fun formatZoom(ratio: Float): String {
    val r = (ratio * 10f).let { kotlin.math.round(it) } / 10f
    val text = if (r == r.toInt().toFloat()) r.toInt().toString() else String.format(Locale.ROOT, "%.1f", r)
    return text.removePrefix("0") + "\u00D7"
}

internal fun scaleToEdge(src: Bitmap, edge: Int): Bitmap {
    val scale = edge.toFloat() / max(src.width, src.height)
    if (scale >= 1f) return src
    val out = Bitmap.createScaledBitmap(
        src, max(1, (src.width * scale).toInt()), max(1, (src.height * scale).toInt()), true,
    )
    if (out !== src) src.recycle()
    return out
}

internal suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val result = runCatching { future.get() }
                result.onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWith(Result.failure(it)) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
