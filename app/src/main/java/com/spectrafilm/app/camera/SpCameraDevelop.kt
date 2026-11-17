/*
 * Argentroom camera ("拍摄直出") — developing a shot through the real engine. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Silverphase iOS develops every camera shot with the native engine and saves the print to
 * Photos (MobileCameraView.takePhoto -> develop -> savePhoto). Here a shot is developed by the
 * SAME code the Library's "export selected photos" uses: [runBatchExport] with a one-photo
 * [BatchEditSource.OwnEdits], inside [ExportWorkRuntime]. That buys everything the export path
 * already guarantees, for free and without a second render pipeline:
 *  - single-flight with any editor/Library export (two full-resolution renders at once is the
 *    OOM #268-#271 removed), the foreground service, GPU release between renders;
 *  - decode with EXIF orientation applied, the engine simulate, grade, border, encode, EXIF copy
 *    (orientation reset to normal) and the MediaStore publish into the app's export album.
 *
 * The edit is "a first open of this photo" (the user's default film, paper, colour space —
 * [ownEditFor] with [AppSettings.applyDefaultsTo]) with the camera's choices on top: the film
 * stock (paper follows it exactly as the editor's film change does, SilverPrint), positive
 * films scanned directly (as the Silverphase editor's setFilm), the engine's auto-exposure
 * metering method and the exposure compensation dragged in the viewfinder. The same edit is
 * saved as the original's recipe, so opening the original in the editor continues from the look
 * that was developed.
 *
 * RAW (C05, Silverphase MobileCameraCapture.captureFormat): on a back camera that can deliver
 * RAW+JPEG, a shot taken at 1x zoom develops from its DNG as a RAW source (scene-linear, like
 * the iOS Bayer/ProRAW path) instead of the camera's processed JPEG, whose sharpening, tone
 * curve and saturation the film model would otherwise simulate a second time. Zoomed shots use
 * the JPEG (a DNG is always the full, uncropped sensor, as iOS refuses Bayer when zoomed), and a
 * DNG that cannot be developed at full size falls back to its JPEG in the same develop.
 *
 * The film format is 24 mm on the short side at the capture's aspect (Silverphase
 * ProcessContract's default `_filmSide: short, _sideLengthMM: 24`), so grain and halation scale
 * as on a 135 frame: 32 mm long edge for a 4:3 shot.
 *
 * One worker drains the queue in order, waiting while another export owns the runtime.
 * Captured files with queued requests survive a process restart.
 */
package com.spectrafilm.app.camera

import com.spectrafilm.app.*
import com.spectrafilm.app.diagnostics.*
import com.spectrafilm.app.export.*
import com.spectrafilm.app.look.*
import com.spectrafilm.app.render.*
import com.spectrafilm.app.settings.*
import com.spectrafilm.app.source.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.spectrafilm.app.settings.AppSettings
import com.spectrafilm.app.export.BatchEditSource
import com.spectrafilm.app.export.BatchExportSource
import com.spectrafilm.app.export.BatchExportTracker
import com.spectrafilm.app.export.BatchPhotoEdit
import com.spectrafilm.app.diagnostics.Diag
import com.spectrafilm.app.render.EngineHolder
import com.spectrafilm.app.export.ExportFormat
import com.spectrafilm.app.export.ExportOptions
import com.spectrafilm.app.export.ExportRuntimeState
import com.spectrafilm.app.export.ExportSize
import com.spectrafilm.app.export.ExportTerminalOutcome
import com.spectrafilm.app.export.ExportWorkRuntime
import com.spectrafilm.app.look.OperationalParamLimits
import com.spectrafilm.app.look.ParamsState
import com.spectrafilm.app.look.Presets
import com.spectrafilm.app.ProcessDispatchers
import com.spectrafilm.app.R
import com.spectrafilm.app.look.RecipeWorkRuntime
import com.spectrafilm.app.look.Recipes
import com.spectrafilm.app.look.SilverPrint
import com.spectrafilm.app.source.SourceKind
import com.spectrafilm.app.look.StockCatalog
import com.spectrafilm.app.appContainer
import com.spectrafilm.app.export.ownEditFor
import com.spectrafilm.app.export.runBatchExport
import com.spectrafilm.app.silverphase.SpCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The engine's auto-exposure metering for a shot (AUTO_EXPOSURE_METHODS ids), or [OFF] to keep
 * the input exposure. Silverphase's camera offers the same idea (MobileMeteringMode); the ids
 * here are the Android engine's own, so nothing is translated on the way in.
 */
internal enum class SpCameraMetering(val method: String?, val labelRes: Int) {
    CENTER_WEIGHTED("center_weighted", R.string.spcam_metering_center_weighted),
    MATRIX("matrix", R.string.spcam_metering_matrix),
    HIGHLIGHT_WEIGHTED("highlight_weighted", R.string.spcam_metering_highlight),
    AVERAGE("average", R.string.spcam_metering_average),
    OFF(null, R.string.spcam_metering_off),
    ;

    companion object {
        fun parse(name: String?): SpCameraMetering = entries.firstOrNull { it.name == name } ?: CENTER_WEIGHTED
    }
}

/** What the camera hands the engine with a shot. */
@Immutable
internal data class SpCameraRecipe(
    val filmId: String,
    val metering: SpCameraMetering,
    /** Film exposure compensation in stops, applied by the engine (-4..4, as Silverphase). */
    val exposureEv: Float,
)

internal enum class SpShotPhase { WAITING, DEVELOPING, DONE, FAILED }

/** One shot of this session, newest last in [SpCameraDevelop.shots]. */
@Immutable
internal data class SpCameraShot(
    val id: Long,
    /**
     * The untouched capture being developed (content:// via SpCameraFileProvider): the DNG for
     * a RAW shot, else the camera JPEG. What the editor opens.
     */
    val original: Uri,
    val name: String,
    val filmName: String,
    val phase: SpShotPhase,
    /** The viewfinder frame frozen at the shutter, shown while the shot develops. */
    val latent: ImageBitmap?,
    /** A small decode of the developed print once it is in the gallery. */
    val developed: ImageBitmap?,
    /** The developed print's MediaStore row, when the platform reported it. */
    val resultUri: Uri?,
    val failed: Boolean = false,
)

internal object SpCameraDevelop {
    /** Exposure-compensation range the viewfinder drag allows (MobileCameraRecipe). */
    const val EV_MIN = -4f
    const val EV_MAX = 4f
    private const val MAX_KEPT = 24
    private const val THUMB_EDGE = 384

    private class Pending(
        val id: Long,
        val app: Context,
        val capture: SpCameraCaptureFiles,
        val recipe: SpCameraRecipe,
        var useDng: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers.default)
    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val ids = AtomicLong(0L)
    private val recoveryStarted = AtomicBoolean(false)
    private val lock = Any()
    private var workerStarted = false
    private val mutable = MutableStateFlow<List<SpCameraShot>>(emptyList())

    /** This process's shots, oldest first; the camera's thumbnail shows the last one. */
    val shots: StateFlow<List<SpCameraShot>> = mutable.asStateFlow()

    fun submit(context: Context, capture: SpCameraCaptureFiles, recipe: SpCameraRecipe, latent: Bitmap?, useDng: Boolean) {
        val app = context.applicationContext
        try {
            capture.enqueue(SpCameraCaptureFiles.Request(recipe.filmId, recipe.metering.name, recipe.exposureEv, useDng))
        } catch (failure: Exception) {
            runCatching { capture.keepOriginal(useDng) }
            throw failure
        }
        enqueue(app, capture, recipe, latent, useDng)
    }

    /** Restore only work from the prior process. A running job may already have published. */
    fun recover(context: Context, priorProcessCutoffMs: Long) {
        if (!recoveryStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        for (capture in SpCameraStorage.previousCaptures(app, priorProcessCutoffMs)) {
            runCatching {
                val queued = capture.readQueued()
                val interrupted = capture.readInterrupted()
                when {
                    interrupted != null -> {
                        val original = capture.keepOriginal(interrupted.useDng)
                        addShot(app, original, interrupted.recipe(), latent = null, phase = SpShotPhase.FAILED)
                    }
                    queued != null && capture.jpeg.length() > 0L ->
                        enqueue(app, capture, queued.recipe(), latent = null, useDng = queued.useDng)
                    else -> capture.discard()
                }
            }.onFailure { Diag.w("camera recovery failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun SpCameraCaptureFiles.Request.recipe(): SpCameraRecipe =
        SpCameraRecipe(film, SpCameraMetering.parse(metering), exposureEv)

    private fun enqueue(app: Context, capture: SpCameraCaptureFiles, recipe: SpCameraRecipe, latent: Bitmap?, useDng: Boolean) {
        val raw = useDng && capture.dng.length() > 0L
        val file = if (raw) capture.dng else capture.jpeg
        val id = addShot(app, file, recipe, latent, SpShotPhase.WAITING)
        ensureWorker()
        queue.trySend(Pending(id, app, capture, recipe, raw)).getOrThrow()
    }

    private fun addShot(app: Context, file: File, recipe: SpCameraRecipe, latent: Bitmap?, phase: SpShotPhase): Long {
        val id = ids.incrementAndGet()
        val shot = SpCameraShot(
            id = id,
            original = SpCameraStorage.uriFor(app, file),
            name = file.name,
            filmName = filmName(app, recipe.filmId),
            phase = phase,
            latent = latent?.asImageBitmap(),
            developed = null,
            resultUri = null,
            failed = phase == SpShotPhase.FAILED,
        )
        mutable.update { (it + shot).takeLast(MAX_KEPT) }
        return id
    }

    fun filmName(context: Context, filmId: String): String =
        SpCatalog.stock(context, filmId)?.name ?: StockCatalog.displayName(context, filmId)

    private fun ensureWorker(): Unit = synchronized(lock) {
        if (workerStarted) return@synchronized
        workerStarted = true
        scope.launch {
            for (pending in queue) {
                try {
                    developOne(pending)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Throwable) {
                    // Never let one shot stop the queue.
                    Diag.w("camera develop crashed: ${failure.javaClass.simpleName}")
                    keepOriginal(pending)
                    setShot(pending.id) { it.copy(phase = SpShotPhase.FAILED, failed = true) }
                }
            }
        }
        Unit
    }

    private fun setShot(id: Long, change: (SpCameraShot) -> SpCameraShot) {
        mutable.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private suspend fun developOne(p: Pending) {
        val outcome = runInExportRuntime(p)
        keepOriginal(p)
        when (outcome) {
            is ExportTerminalOutcome.Success -> {
                val published = outcome.publishedUri?.let(Uri::parse)
                val thumb = published?.let { withContext(ProcessDispatchers.io) { decodeThumb(p.app, it) } }
                setShot(p.id) { it.copy(phase = SpShotPhase.DONE, developed = thumb, resultUri = published) }
                Diag.i("camera develop done in ${outcome.totalMs} ms")
            }
            is ExportTerminalOutcome.Failure -> {
                Diag.w("camera develop failed: ${outcome.cause.javaClass.simpleName}")
                setShot(p.id) { it.copy(phase = SpShotPhase.FAILED, failed = true) }
            }
            is ExportTerminalOutcome.Cancelled -> {
                // Cancelled from the Library/editor progress card: the original is kept and can
                // still be opened and exported from the editor.
                setShot(p.id) { it.copy(phase = SpShotPhase.FAILED, failed = true) }
            }
        }
        // The batch tracker drives the Library's export card; a camera develop is not a batch
        // the user started there, so clear its finished result instead of leaving the card up.
        BatchExportTracker.state.value?.let { if (it.finished && it.total == 1) BatchExportTracker.acknowledge() }
    }

    private suspend fun keepOriginal(p: Pending) {
        withContext(ProcessDispatchers.io) {
            runCatching {
                val file = p.capture.keepOriginal(p.useDng)
                val uri = SpCameraStorage.uriFor(p.app, file)
                setShot(p.id) { it.copy(original = uri, name = file.name) }
            }.onFailure { Diag.w("camera could not keep original: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Run one develop as an [ExportWorkRuntime] job, waiting while another export holds the
     * runtime. Returns the job's terminal outcome; never throws for a render failure.
     */
    private suspend fun runInExportRuntime(p: Pending): ExportTerminalOutcome {
        val app = p.app
        val settings = AppSettings.from(app)
        settings.awaitLoaded()
        val options = ExportOptions(
            format = ExportFormat.JPEG,
            jpegQuality = settings.exportQuality,
            size = ExportSize.FULL,
            customLongEdge = 0,
            // e.g. Argentroom_20261115_124401_382_Kodak_Portra_400 (ExportOptions.exportBaseName).
            customName = "{name}_{film}",
            border = settings.exportBorder,
            frame = settings.exportFrame,
        )
        val keepGps = settings.exportKeepGps
        while (true) {
            val started = System.currentTimeMillis()
            val result = CompletableDeferred<ExportTerminalOutcome>()
            val runId = ExportWorkRuntime.launch(app, options.format, started) {
                try {
                    withContext(ProcessDispatchers.io) { p.capture.start() }
                    developWork(app, p, settings, options, keepGps, started).also { result.complete(it) }
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                    throw failure
                }
            }
            if (runId == null) {
                // Another export (the editor's, a Library batch) is rendering: wait for it.
                ExportWorkRuntime.state.first { it !is ExportRuntimeState.Running }
                delay(200)
                continue
            }
            // Ours is the run now (a wait for an editor export stays WAITING).
            setShot(p.id) { it.copy(phase = SpShotPhase.DEVELOPING) }
            ExportWorkRuntime.state.first { it !is ExportRuntimeState.Running || it.runId != runId }
            val claimed = ExportWorkRuntime.claimFinished(runId)
            if (claimed is ExportTerminalOutcome.Success) claimed.bitmap?.recycle()
            if (result.isCompleted) {
                // Completed: await() returns (or throws) immediately.
                return try {
                    result.await()
                } catch (failure: CancellationException) {
                    ExportTerminalOutcome.Cancelled(options.format, 0L, System.currentTimeMillis() - started)
                } catch (failure: Throwable) {
                    ExportTerminalOutcome.Failure(options.format, 0L, System.currentTimeMillis() - started, failure)
                }
            }
            // Cancelled before the work began.
            return claimed ?: ExportTerminalOutcome.Cancelled(options.format, 0L, System.currentTimeMillis() - started)
        }
    }

    private suspend fun developWork(
        app: Context,
        p: Pending,
        settings: AppSettings,
        options: ExportOptions,
        keepGps: Boolean,
        started: Long,
    ): ExportTerminalOutcome {
        val io = ProcessDispatchers.io
        val engine = withContext(io) { EngineHolder.get(app) }
        val profiles = withContext(io) { runCatching { engine.listProfiles() }.getOrDefault(emptyList()) }
        val jpeg = SpCameraStorage.uriFor(app, p.capture.jpeg)
        // Use the JPEG header if the develop is interrupted before a full decode. The
        // successfully decoded image supplies the actual frame dimensions.
        var aspect = withContext(io) { captureAspect(app, jpeg) }
        fun editFor(): BatchPhotoEdit = cameraEdit(app, p.recipe, profiles, settings, aspect)
        fun applyDecodedAspect(state: ParamsState, decodedAspect: Float) {
            aspect = decodedAspect
            applyCameraFilmFormat(state, decodedAspect)
        }
        suspend fun develop(source: BatchExportSource, onDecodeFailure: ((Throwable) -> Unit)? = null) =
            runBatchExport(
                app, engine, listOf(source), BatchEditSource.OwnEdits { editFor() }, options, keepGps, started,
                onDecodeFailure = onDecodeFailure, onImageDecoded = ::applyDecodedAspect,
            )
        suspend fun developJpeg() = develop(BatchExportSource(jpeg, p.capture.jpeg.name, SourceKind.PHOTO))

        val outcome = if (p.useDng) {
            val raw = SpCameraStorage.uriFor(app, p.capture.dng)
            var decodeFailure: Throwable? = null
            val rawOutcome = develop(BatchExportSource(raw, p.capture.dng.name, SourceKind.RAW, metadataUri = jpeg)) {
                decodeFailure = it
            }
            if (rawOutcome is ExportTerminalOutcome.Failure && decodeFailure != null) {
                Diag.w("camera RAW decode failed (${decodeFailure?.javaClass?.simpleName}); developing the JPEG")
                p.useDng = false
                withContext(io) { p.capture.dng.delete() }
                setShot(p.id) { it.copy(original = jpeg, name = p.capture.jpeg.name) }
                developJpeg()
            } else {
                rawOutcome
            }
        } else {
            developJpeg()
        }
        // The editor opens the retained original. Its recipe has the same frame and look.
        runCatching {
            val file = p.capture.original(p.useDng)
            val key = Recipes.keyFor(SpCameraStorage.uriFor(app, file))
            if (key != null) {
                val json = Presets.toJsonString(editFor().state)
                RecipeWorkRuntime.submit { app.appContainer.recipes.saveJson(key, json, file.name) }.await()
            }
        }.onFailure { if (it is CancellationException) throw it else Diag.w("camera recipe save failed: ${it.javaClass.simpleName}") }
        return outcome
    }

    /** A first open's settings with the camera's choices on top (see the file comment). */
    internal fun cameraEdit(
        context: Context,
        recipe: SpCameraRecipe,
        profiles: List<String>,
        settings: AppSettings,
        /** The capture's width / height (either orientation), or null when unknown (4:3). */
        captureAspect: Float? = null,
    ): BatchPhotoEdit {
        val edit = ownEditFor(null, profiles, settings::applyDefaultsTo)
        val s = edit.state
        applyCameraFilmFormat(s, captureAspect)
        val film = recipe.filmId
        if (film.isNotBlank() && profiles.isNotEmpty()) {
            check(film in profiles) { "film profile $film is not installed" }
        }
        val oldFilm = s.filmProfile
        if (film.isNotBlank() && film != oldFilm) {
            s.filmProfile = film
            val oldPrint = s.printProfile
            val newPrint = SilverPrint.printAfterFilmChange(
                oldFilm = oldFilm, newFilm = film, print = oldPrint,
                lastColourPrint = s.lastColourPrint, available = profiles,
                targetPrint = SpCatalog.stock(context, film)?.targetPrint,
            )
            s.lastColourPrint = SilverPrint.colourPrintToRemember(
                newFilm = film, oldPrint = oldPrint, newPrint = newPrint, remembered = s.lastColourPrint,
            )
            if (newPrint != oldPrint) s.printProfile = newPrint
        }
        // Silverphase: a positive (slide) film is scanned directly, never printed.
        if (SpCatalog.stock(context, film)?.isPositive == true) s.scanFilm = true
        val method = recipe.metering.method
        s.autoExposure = method != null
        if (method != null) s.autoExposureMethod = method
        s.exposureCompensationEv = recipe.exposureEv.coerceIn(EV_MIN, EV_MAX)
        return edit
    }

    /** Width / height of an encoded capture, from its header; null when unreadable. */
    private fun captureAspect(context: Context, uri: Uri): Float? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight else null
    }.getOrNull()

    /** Small, upright decode of a gallery item for the thumbnail. Null when unreadable. */
    private fun decodeThumb(context: Context, uri: Uri): ImageBitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return@runCatching null
        var sample = 1
        while (longEdge / (sample * 2) >= THUMB_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        // The export writes orientation NORMAL (pixels are already upright), so no EXIF turn.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.let { bmp ->
            val scale = THUMB_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                    .also { if (it !== bmp) bmp.recycle() }
            } else bmp
            out.prepareToDraw()
            out.asImageBitmap()
        }
    }.getOrNull()
}

/** Short side of a camera shot's film frame in mm: a 135 frame's 24 mm (Silverphase default). */
internal const val CAMERA_FILM_SHORT_SIDE_MM = 24f

/**
 * film_format_mm (the long edge) for a camera shot of [aspect] (width / height, either
 * orientation): 24 mm x the aspect, as Silverphase ProcessContract derives it for
 * `_filmSide: short`, kept inside the engine's operational range. 4:3 -> 32 mm, 3:2 -> 36 mm,
 * 16:9 -> 42.7 mm. A non-finite or non-positive aspect is treated as 4:3.
 */
internal fun cameraFilmFormatMm(aspect: Float): Float {
    val a = if (aspect.isFinite() && aspect > 0f) maxOf(aspect, 1f / aspect) else 4f / 3f
    return (CAMERA_FILM_SHORT_SIDE_MM * a)
        .coerceIn(4f, 200f)
        .coerceIn(OperationalParamLimits.FILM_FORMAT_MM.start, OperationalParamLimits.FILM_FORMAT_MM.endInclusive)
}

/** Set [state]'s film format for a camera shot of [aspect] (null: unknown, taken as 4:3). */
internal fun applyCameraFilmFormat(state: ParamsState, aspect: Float?) {
    state.filmFormatMm = cameraFilmFormatMm(aspect ?: (4f / 3f))
}
