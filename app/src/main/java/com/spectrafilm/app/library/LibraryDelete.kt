/*
 * Argentroom — deleting photos from the Library. GPLv3.
 *
 * Two kinds of frame reach the Library and each is deleted the way its owner allows:
 *  - a frame of the chosen folder is a SAF document; DocumentsContract.deleteDocument removes it
 *    through the folder's persisted grant (the picker's write grant, kept by takeGrant);
 *  - a phone-gallery frame is a MediaStore item. Android 11+ moves it to the system trash after
 *    the system's own confirmation (MediaStore.createTrashRequest), so it can still be restored
 *    from the Photos app for 30 days, as Silverphase's delete goes to "Recently Deleted" on iOS.
 *    Android 10 and older delete directly; an item another app owns is reported as not deleted.
 *
 * The app's own confirmation comes first for both, naming how many photos go. The saved edit
 * (recipe) of a deleted frame is left in place: it is keyed by URI, harmless, and lets an undo
 * of the system trash bring the edit back with the photo.
 */
package com.spectrafilm.app.library

import com.spectrafilm.app.R
import com.spectrafilm.app.ProcessDispatchers
import com.spectrafilm.app.diagnostics.Diag
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a delete did: [deleted] frames are gone, [failed] could not be removed. */
internal data class PhotoDeleteResult(val deleted: Int, val failed: Int)

/** Asks, then deletes; see the file comment. Get one with [rememberPhotoDeleter]. */
@Stable
internal class PhotoDeleter internal constructor() {
    internal var pending by mutableStateOf<List<LibraryEntry>>(emptyList())

    /** Opens the confirmation for [entries]; nothing is deleted until the user confirms. */
    fun request(entries: List<LibraryEntry>) {
        if (entries.isNotEmpty()) pending = entries
    }
}

/**
 * The Library's deleter. [onDone] runs on the main thread once every frame has been handled
 * (after the system trash dialog for gallery frames), so the caller can refresh its grid and
 * clear its selection.
 */
@Composable
internal fun rememberPhotoDeleter(onDone: (PhotoDeleteResult) -> Unit): PhotoDeleter {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val deleter = remember { PhotoDeleter() }
    // Folder deletions already done, waiting for the system trash dialog's answer.
    var documentsDone by remember { mutableStateOf(PhotoDeleteResult(0, 0)) }
    var mediaCount by remember { mutableStateOf(0) }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ok = r.resultCode == Activity.RESULT_OK
        val d = documentsDone
        onDone(
            PhotoDeleteResult(
                deleted = d.deleted + if (ok) mediaCount else 0,
                failed = d.failed + if (ok) 0 else mediaCount,
            ),
        )
    }

    val entries = deleter.pending
    if (entries.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleter.pending = emptyList() },
            title = {
                Text(pluralStringResource(R.plurals.library_delete_title, entries.size, entries.size))
            },
            text = { Text(stringResource(R.string.library_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = entries
                    deleter.pending = emptyList()
                    scope.launch {
                        val (media, documents) = picked.map { Uri.parse(it.documentUri) }
                            .partition { it.authority == MediaStore.AUTHORITY }
                        val docResult = withContext(ProcessDispatchers.io) {
                            deleteDocuments(ctx, documents)
                        }
                        if (media.isEmpty()) {
                            onDone(docResult)
                            return@launch
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            documentsDone = docResult
                            mediaCount = media.size
                            val sender = runCatching {
                                MediaStore.createTrashRequest(ctx.contentResolver, media, true).intentSender
                            }.onFailure { Diag.w("library trash request failed: $it") }.getOrNull()
                            if (sender != null) {
                                trash.launch(IntentSenderRequest.Builder(sender).build())
                            } else {
                                onDone(PhotoDeleteResult(docResult.deleted, docResult.failed + media.size))
                            }
                        } else {
                            val legacy = withContext(ProcessDispatchers.io) {
                                deleteMediaLegacy(ctx.contentResolver, media)
                            }
                            onDone(
                                PhotoDeleteResult(
                                    docResult.deleted + legacy.deleted,
                                    docResult.failed + legacy.failed,
                                ),
                            )
                        }
                    }
                }) { Text(stringResource(R.string.library_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleter.pending = emptyList() }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    return deleter
}

private fun deleteDocuments(context: Context, uris: List<Uri>): PhotoDeleteResult {
    var deleted = 0
    var failed = 0
    for (uri in uris) {
        val ok = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .onFailure { Diag.w("library delete failed for a folder photo: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        if (ok) deleted++ else failed++
    }
    return PhotoDeleteResult(deleted, failed)
}

/** Android 10 and older: a direct delete; an item owned by another app throws and is counted failed. */
private fun deleteMediaLegacy(resolver: ContentResolver, uris: List<Uri>): PhotoDeleteResult {
    var deleted = 0
    var failed = 0
    for (uri in uris) {
        val rows = runCatching { resolver.delete(uri, null, null) }
            .onFailure { Diag.w("library delete failed for a gallery photo: ${it.javaClass.simpleName}") }
            .getOrDefault(0)
        if (rows > 0) deleted++ else failed++
    }
    return PhotoDeleteResult(deleted, failed)
}
