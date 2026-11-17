// SPDX-License-Identifier: GPL-3.0-or-later
package com.spectrafilm.app.camera

import java.io.File
import java.io.IOException
import java.util.Properties

/** A capture is staged until its selected original is kept for the editor. */
internal class SpCameraCaptureFiles(private val directory: File, private val stem: String) {
    data class Request(val film: String, val metering: String, val exposureEv: Float, val useDng: Boolean)

    private val staging = File(directory, STAGING)
    val jpeg = File(staging, "$stem.jpg")
    val dng = File(staging, "$stem.dng")
    private val pending = File(staging, "$stem.pending")
    private val running = File(staging, "$stem.running")
    private val temporary = File(staging, "$stem.tmp")
    private val staged = listOf(jpeg, dng, pending, running, temporary)

    fun prepare() {
        if (!staging.isDirectory && !staging.mkdirs() && !staging.isDirectory) {
            throw IOException("camera staging directory unavailable")
        }
    }

    fun alreadyExists(): Boolean =
        staged.any(File::exists) || original(true).exists() || original(false).exists()

    fun enqueue(request: Request) {
        val properties = Properties().apply {
            setProperty("film", request.film)
            setProperty("metering", request.metering)
            setProperty("exposureEv", request.exposureEv.toString())
            setProperty("useDng", request.useDng.toString())
        }
        try {
            temporary.outputStream().use {
                properties.store(it, null)
                it.fd.sync()
            }
            move(temporary, pending)
        } finally {
            temporary.delete()
        }
    }

    fun start() = move(pending, running)

    fun readQueued(): Request? = read(pending)

    fun readInterrupted(): Request? = read(running)

    private fun read(marker: File): Request? = if (!marker.isFile) null else runCatching {
        val properties = Properties().apply { marker.inputStream().use { load(it) } }
        Request(
            film = properties.getProperty("film").orEmpty(),
            metering = properties.getProperty("metering").orEmpty(),
            exposureEv = properties.getProperty("exposureEv")?.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0f,
            useDng = properties.getProperty("useDng") == "true",
        )
    }.getOrNull()

    fun original(useDng: Boolean): File = File(directory, if (useDng) dng.name else jpeg.name)

    fun keepOriginal(useDng: Boolean): File {
        // A process can die between the rename and journal removal. Prefer the existing final
        // original in that case rather than overwriting it.
        val retained = listOf(original(useDng), original(!useDng)).firstOrNull { it.length() > 0L }
        val destination = retained ?: run {
            val takeDng = useDng && dng.length() > 0L
            val source = if (takeDng) dng else jpeg
            if (source.length() <= 0L) throw IOException("camera original missing")
            original(takeDng).also { move(source, it) }
        }
        discard()
        return destination
    }

    fun discard() {
        for (file in staged) file.delete()
    }

    private fun move(source: File, destination: File) {
        if (!source.renameTo(destination)) throw IOException("could not move camera capture ${source.extension}")
    }

    companion object {
        private const val STAGING = "staging"

        /** Format the local shutter date embedded in app-generated camera names for EXIF. */
        fun shutterDate(filename: String, prefix: String): String? {
            val pattern = Regex(Regex.escape(prefix) + """_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})_\d{3}(?:_\d+)?\.(?:jpg|dng)""")
            val parts = pattern.matchEntire(filename)?.groupValues ?: return null
            return "${parts[1]}:${parts[2]}:${parts[3]} ${parts[4]}:${parts[5]}:${parts[6]}"
        }

        fun previousCaptures(directory: File, prefix: String, beforeMillis: Long): List<SpCameraCaptureFiles> {
            val captureName = Regex(Regex.escape(prefix) + """_\d{8}_\d{6}_\d{3}(?:_\d+)?""")
            return File(directory, STAGING).listFiles().orEmpty()
                .filter { it.isFile && it.lastModified() < beforeMillis }
                .map { it.nameWithoutExtension }
                .filter(captureName::matches)
                .distinct()
                .sorted()
                .map { SpCameraCaptureFiles(directory, it) }
        }
    }
}
