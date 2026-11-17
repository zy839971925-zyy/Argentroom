/* Argentroom for Android — request-scoped Library thumbnails. GPLv3. */
package com.argentroom.app.library

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.argentroom.app.silverphase.rememberSpLoadedValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Composable
internal fun rememberLibraryThumbnail(uri: String?, raw: Boolean, dispatcher: CoroutineDispatcher = thumbnailLane): Bitmap? {
    val context = LocalContext.current.applicationContext
    return rememberSpLoadedValue(
        uri?.let { it to raw },
        cached = { (source, _) -> LibraryThumbnails.cached(source) },
        load = { (source, isRaw) -> withContext(dispatcher) { LibraryThumbnails.load(context, source, isRaw) } },
    )
}
