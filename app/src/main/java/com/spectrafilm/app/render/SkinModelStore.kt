/*
 * Spektrafilm for Android — bundled skin segmentation model. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The model (Google MediaPipe "Selfie Multiclass 256x256", float32 v1, Apache 2.0, 16 371 837 bytes)
 * ships inside the APK as assets/models/selfie_multiclass_256x256.tflite (Argentroom C06: the app
 * makes no network requests at runtime). Its licence and source are in NOTICE.md (assets/legal/spektrafilm/NOTICE.md).
 * The first skin mask copies it into no-backup app storage, under the same integrity gate the old
 * downloader used: the copy must be exactly [Pin.sizeBytes] long and hash to the compiled-in
 * [Pin.sha256], or it is discarded. Nothing partial is ever left under the final name.
 */
package com.spectrafilm.app.render

import com.spectrafilm.app.diagnostics.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SkinModelStore(
    private val directory: File,
    private val io: CoroutineDispatcher,
    private val pin: Pin = Pin.SELFIE_MULTICLASS,
    private val warn: (String) -> Unit = Diag::w,
    /** Opens the bundled model bytes ([Pin.assetPath] in the APK); null when unavailable. */
    private val openBundled: () -> InputStream?,
) {
    /** The one file this store accepts: where it is bundled, its upstream source, and the bytes it must be. */
    data class Pin(
        val assetPath: String,
        val sourceUrl: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
    ) {
        companion object {
            val SELFIE_MULTICLASS = Pin(
                assetPath = "models/selfie_multiclass_256x256.tflite",
                // Provenance only; never fetched at runtime.
                sourceUrl = "https://storage.googleapis.com/mediapipe-models/image_segmenter/" +
                    "selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite",
                fileName = "selfie_multiclass_256x256.tflite",
                sizeBytes = 16_371_837L,
                sha256 = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0",
            )
        }
    }

    private val lock = Mutex()

    @Volatile
    private var verified: File? = null

    private val target: File get() = File(directory, pin.fileName)

    /** The verified model file if it is already unpacked, without reading the APK. */
    suspend fun cached(): File? = lock.withLock { withContext(io) { verifiedOrNull() } }

    /**
     * The verified model file, unpacking the bundled copy first when absent or damaged. Null when the
     * bundled bytes are missing or fail the size/digest gate, or the copy is cancelled.
     */
    suspend fun ensure(onProgress: (Long) -> Unit = {}): File? = lock.withLock {
        withContext(io) {
            verifiedOrNull()?.let { return@withContext it }
            if (!directory.isDirectory && !directory.mkdirs()) return@withContext null
            val partial = File(directory, "${pin.fileName}.part")
            val context = coroutineContext
            val ok = runCatching {
                val body = openBundled() ?: run {
                    warn("skin model missing from the APK")
                    return@runCatching false
                }
                val sha = body.use { input ->
                    partial.outputStream().use { out -> copyExact(input, out, pin.sizeBytes, { context.isActive }, onProgress) }
                }
                if (sha != pin.sha256) {
                    if (sha != null) warn("skin model digest mismatch")
                    return@runCatching false
                }
                partial.renameTo(target)
            }.getOrDefault(false)
            partial.delete()
            if (ok) target.also { verified = it } else null
        }
    }

    /** Remove the model (e.g. from a storage setting). The next [ensure] unpacks it again. */
    suspend fun clear() = lock.withLock {
        withContext(io) {
            verified = null
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    private fun verifiedOrNull(): File? {
        val file = target
        if (verified == file && file.length() == pin.sizeBytes) return file
        verified = null
        if (!file.isFile || file.length() != pin.sizeBytes) return null
        if (sha256(file) != pin.sha256) {
            warn("skin model on disk is damaged; discarding")
            file.delete()
            return null
        }
        verified = file
        return file
    }

    companion object {
        /** Copies exactly [expectedSize] bytes and returns their SHA-256, or null on a size mismatch or cancel. */
        internal fun copyExact(
            input: InputStream,
            output: java.io.OutputStream,
            expectedSize: Long,
            keepGoing: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): String? {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                if (!keepGoing()) return null
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > expectedSize) return null
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
                onProgress(total)
            }
            if (total != expectedSize) return null
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
