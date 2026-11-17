// SPDX-License-Identifier: GPL-3.0-or-later
package com.spectrafilm.app.camera

/** Collects CameraX's on-disk callbacks on the main thread. A result is emitted once. */
internal class SpCameraCaptureResult(private val expectsDng: Boolean, private val useDng: Boolean) {
    enum class SavedImage { JPEG, DNG }
    enum class Input { JPEG, RAW, FAILED }

    private var jpegSaved = false
    private var dngSaved = false
    private var hadError = false
    var input: Input? = null
        private set

    fun saved(image: SavedImage): Input? {
        if (input != null) return null
        when (image) {
            SavedImage.JPEG -> jpegSaved = true
            SavedImage.DNG -> dngSaved = true
        }
        return if (jpegSaved && (!expectsDng || dngSaved || hadError)) settle() else null
    }

    fun error(): Input? {
        if (input != null) return null
        hadError = true
        // Processing the two formats can finish in either order, even if one save fails.
        return if (jpegSaved || !expectsDng) settle() else null
    }

    fun timeout(): Input? = if (input == null) settle() else null

    private fun settle(): Input = when {
        !jpegSaved -> Input.FAILED
        useDng && dngSaved && !hadError -> Input.RAW
        else -> Input.JPEG
    }.also { input = it }
}
