/*
 * Argentroom camera ("拍摄直出") — where captured originals live. GPLv3.
 *
 * The selected untouched original of each shot is kept in app-private storage (filesDir/camera)
 * and served as a content:// source by this app's own, non-exported FileProvider. The editor
 * accepts as a source (SourceAccess only takes content:// URIs) and what a restart can still
 * read: SourceAccess restores a TRANSIENT reference whenever it is still readable, and this
 * provider always is. The film-simulated print goes to the system gallery through the export
 * path (SpCameraDevelop); the original never leaves the app unless the user exports it.
 *
 * Kept separate from SharedSourceCopies / AlbumImports on purpose: both of those prune their
 * directories down to the editor's current source, which would delete a roll of camera
 * originals the moment another photo is opened.
 */
package com.argentroom.app.camera

import com.argentroom.app.export.*
import com.argentroom.app.library.*
import com.argentroom.app.source.*
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.argentroom.app.export.EXPORT_BRAND
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The camera's own FileProvider; a subclass so it can sit beside the shared-source one. */
class SpCameraFileProvider : FileProvider()

internal object SpCameraStorage {
    /** filesDir sub-directory; must match res/xml/camera_capture_paths.xml. */
    const val DIR = "camera"
    private const val PATH_NAME = "captures"

    fun authority(context: Context): String = "${context.packageName}.camera"

    fun directory(context: Context): File = File(context.applicationContext.filesDir, DIR)

    /** Capture file prefix: the product name every export carries (ExportOptions.EXPORT_BRAND). */
    private const val PREFIX = EXPORT_BRAND

    /**
     * A fresh, unique pair of targets for one capture (`<stem>.jpg` and `<stem>.dng`); neither
     * exists yet. The DNG is only written when the camera captures RAW+JPEG.
     */
    fun newCapture(context: Context, nowMs: Long = System.currentTimeMillis()): SpCameraCaptureFiles {
        val dir = directory(context)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(nowMs))
        var capture = SpCameraCaptureFiles(dir, "${PREFIX}_$stamp")
        capture.prepare()
        var n = 1
        while (capture.alreadyExists()) capture = SpCameraCaptureFiles(dir, "${PREFIX}_${stamp}_${n++}")
        return capture
    }

    fun previousCaptures(context: Context, beforeMillis: Long): List<SpCameraCaptureFiles> =
        SpCameraCaptureFiles.previousCaptures(directory(context), PREFIX, beforeMillis)

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context.applicationContext, authority(context), file)

    fun isDng(context: Context, uri: Uri): Boolean =
        isCapture(context, uri) && uri.lastPathSegment?.endsWith(".dng", ignoreCase = true) == true

    fun shutterDate(context: Context, uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { isCapture(context, uri) }
            ?.let { SpCameraCaptureFiles.shutterDate(it, PREFIX) }

    /** True when [uri] is one of this camera's originals. */
    fun isCapture(context: Context, uri: Uri?): Boolean =
        uri?.toString()?.startsWith("content://${authority(context)}/$PATH_NAME/") == true
}
