/*
 * Argentroom camera ("拍摄直出") — the full-screen camera. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Modelled on Silverphase iOS MobileCameraView (MobileCamera.swift): a back chip and a glass
 * pill of finder menus on top (flash, metering, composition aids), the viewfinder with the
 * golden/thirds grid, the level and the focus square, then the finder row (flip, lens/zoom
 * chips) and the capture dock (last shot, shutter, film). Silverphase's look tokens
 * (silverphase/SpDesign.kt: #181818 canvas, #FF9F0A accent, ChromeSurface glass) are reused so
 * the camera reads as part of the same app.
 *
 * The viewfinder is the RAW camera stream and says so: this port has no real-time engine path
 * for camera frames (the GPU LUT loupe is editor-only and off by default), and a stand-in
 * colour filter would misrepresent the film. The film look appears when the shot develops
 * through the real engine (SpCameraDevelop), which the thumbnail shows as it lands.
 *
 * Capture: CameraX ImageCapture writes the JPEG straight into app storage with its EXIF
 * orientation set from the device orientation (OrientationEventListener -> targetRotation);
 * the export decode applies that orientation, so the print is upright whichever way the phone
 * was held, even with the Activity itself not rotating.
 *
 * RAW (C05, Silverphase "Prefer RAW capture", on by default): when the back camera reports
 * OUTPUT_FORMAT_RAW_JPEG, ImageCapture is bound in that format and every shot writes a DNG
 * beside its JPEG. A shot at 1x develops from the DNG (SpCameraDevelop); a zoomed shot keeps
 * only the JPEG, since the DNG is the whole, uncropped sensor (iOS likewise refuses Bayer RAW
 * at any zoom other than 1). A device or lens without RAW, or a bind that refuses it, simply
 * captures JPEG.
 */
package com.argentroom.app.camera

import com.argentroom.app.*
import com.argentroom.app.diagnostics.*
import com.argentroom.app.editor.*
import com.argentroom.app.export.*
import com.argentroom.app.settings.*
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.argentroom.app.settings.AppSettings
import com.argentroom.app.diagnostics.Diag
import com.argentroom.app.export.ExportWorkRuntime
import com.argentroom.app.R
import com.argentroom.app.camera.SpCameraCaptureResult.Input
import com.argentroom.app.camera.SpCameraCaptureResult.SavedImage
import com.argentroom.app.silverphase.SpCatalog
import com.argentroom.app.silverphase.SpChromeButton
import com.argentroom.app.silverphase.SpDesign
import com.argentroom.app.silverphase.SpGlyph
import com.argentroom.app.silverphase.SpHaptics
import com.argentroom.app.silverphase.SpMotion
import com.argentroom.app.silverphase.SpStock
import com.argentroom.app.silverphase.SpStyle
import com.argentroom.app.silverphase.SpText
import com.argentroom.app.silverphase.rememberSpReduceMotion
import com.argentroom.app.silverphase.spChromeSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * The camera. Full screen; draws its own dark canvas and pads itself for the system bars.
 *
 * @param onClose leave the camera (the back chip, system back).
 * @param onOpenInEditor open a shot's ORIGINAL (a content:// URI of this app's camera
 *   FileProvider) in the editor; its recipe already carries the film it was developed with.
 */
@Composable
internal fun SpCameraScreen(
    onClose: () -> Unit,
    onOpenInEditor: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    SpText.bind(ctx.resources)
    val prefs = remember(ctx) { SpCameraPrefs(ctx.applicationContext) }
    val hasHardware = remember(ctx) {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    BackHandler(onBack = onClose)

    val permissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            // Gallery export below API 29 writes public Pictures storage (GalleryExport.kt).
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
    fun cameraGranted() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraGranted()) }
    var asked by remember { mutableStateOf(prefs.askedPermission) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.askedPermission = true
        asked = true
        granted = cameraGranted()
    }
    // Back from system Settings (or a revoke while away): re-read the grant.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = cameraGranted() }
    // Opening the camera IS the context for the request: ask once, straight away. After that
    // the rationale card asks, or sends the user to Settings once Android stops showing it.
    LaunchedEffect(hasHardware) {
        if (hasHardware && !granted && !asked) runCatching { launcher.launch(permissions) }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(SpStyle.canvas),
    ) {
        when {
            !hasHardware -> SpCameraNotice(
                title = stringResource(R.string.spcam_title),
                body = stringResource(R.string.spcam_unavailable),
            )
            !granted -> {
                val activity = remember(ctx) { ctx.findActivity() }
                val blocked = asked && activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
                SpCameraNotice(
                    title = stringResource(R.string.spcam_permission_title),
                    body = stringResource(if (blocked) R.string.spcam_permission_denied else R.string.spcam_permission_body),
                    action = stringResource(if (blocked) R.string.spcam_open_settings else R.string.spcam_permission_allow),
                    onAction = {
                        if (blocked) {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        } else {
                            runCatching { launcher.launch(permissions) }
                        }
                    },
                )
            }
            else -> SpCameraContent(prefs = prefs, onClose = onClose, onOpenInEditor = onOpenInEditor)
        }
        if (!granted || !hasHardware) {
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
                SpRoundGlass {
                    SpChromeButton(stringResource(R.string.spcam_back), SpGlyph.BACK, onClick = onClose)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------------------------------

internal enum class GridMode { OFF, THIRDS, GOLDEN }

@Composable
private fun SpCameraContent(
    prefs: SpCameraPrefs,
    onClose: () -> Unit,
    onOpenInEditor: (Uri) -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val view = LocalView.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val reduceMotion by rememberSpReduceMotion()
    val configuration = LocalConfiguration.current
    val portraitDisplay = configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    // --- persisted choices -------------------------------------------------------------------
    val films: List<SpStock> = remember(ctx) { SpCatalog.films(ctx) }
    var filmId by remember { mutableStateOf(initialFilm(prefs, AppSettings.from(app).defaultFilmProfile, films)) }
    var flashMode by remember { mutableIntStateOf(prefs.flash) }
    var grid by remember { mutableStateOf(prefs.grid) }
    var showLevel by remember { mutableStateOf(prefs.level) }
    var metering by remember { mutableStateOf(prefs.metering) }
    var wantFront by remember { mutableStateOf(prefs.front) }
    var prefersRaw by remember { mutableStateOf(prefs.prefersRaw) }
    var exposureEv by remember { mutableFloatStateOf(0f) }

    // --- camera runtime ----------------------------------------------------------------------
    val previewView = remember(ctx) {
        PreviewView(ctx).apply {
            // TextureView: Compose can clip/fade it and getBitmap() freezes the frame.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // The box around it has the capture's own aspect, so FILL == FIT: what is framed is
            // what is taken.
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(0xFF181818.toInt())
        }
    }
    var retry by remember { mutableIntStateOf(0) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraIssue by remember { mutableStateOf(false) }
    LaunchedEffect(retry) {
        // Only report a failure here; the bind below owns clearing the issue.
        val result = runCatching { awaitCameraProvider(app) }
        result.onSuccess { provider = it }.onFailure { cameraIssue = true }
    }
    val hasBack = remember(provider) { provider?.let { p -> runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false) } ?: false }
    val hasFront = remember(provider) { provider?.let { p -> runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false) } ?: false }
    val noLens = provider != null && !hasBack && !hasFront
    val useFront = (wantFront && hasFront) || (!hasBack && hasFront)

    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<ZoomState?>(null) }
    /** The bound camera can deliver RAW+JPEG (whether or not the user prefers it). */
    var rawAvailable by remember { mutableStateOf(false) }
    var deviceRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }

    DisposableEffect(provider, useFront, lifecycleOwner, retry, prefersRaw) {
        val prov = provider
        if (prov == null || noLens) return@DisposableEffect onDispose { }
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        // ~12 MP, as Silverphase's default (prefers12MP): a full-resolution develop of a 50-200 MP
        // sensor's remosaic output would take minutes and the export decode caps the edge anyway.
        // C05: aim at 4000x3000 and prefer the nearest size AT OR ABOVE it. The old rule
        // (4032x3024, closest lower first) skipped the 4080x3072 binned output many sensors
        // offer and fell to an 8 MP (3264x2448) or smaller frame -- the soft, low-resolution
        // shots seen when the camera was pulled in C04.
        fun buildCapture(raw: Boolean): ImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setTargetRotation(deviceRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(fourThree)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .apply { if (raw) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
            .build()
        // RAW+JPEG only on the back camera (Silverphase: no RAW for the front/portrait capture).
        val rawCapable = !useFront && runCatching {
            ImageCapture.OUTPUT_FORMAT_RAW_JPEG in
                ImageCapture.getImageCaptureCapabilities(prov.getCameraInfo(selector)).supportedOutputFormats
        }.getOrDefault(false)
        rawAvailable = rawCapable
        fun bind(c: ImageCapture) = runCatching { prov.bindToLifecycle(lifecycleOwner, selector, preview, c) }
        var capture = buildCapture(rawCapable && prefersRaw)
        var bound = bind(capture)
        if (bound.isFailure && capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG) {
            // Advertised but refused with this preview/resolution: shoot JPEG rather than nothing.
            Diag.w("camera: RAW+JPEG bind refused (${bound.exceptionOrNull()?.javaClass?.simpleName}); JPEG only")
            rawAvailable = false
            runCatching { prov.unbind(preview, capture) }
            capture = buildCapture(false)
            bound = bind(capture)
        }
        val cam = bound.getOrNull()
        camera = cam
        imageCapture = if (cam != null) capture else null
        cameraIssue = cam == null
        val stateObserver = Observer<CameraState> { st ->
            cameraIssue = st.error?.type == CameraState.ErrorType.CRITICAL
        }
        val zoomObserver = Observer<ZoomState> { zoom = it }
        val streamObserver = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        cam?.cameraInfo?.cameraState?.observe(lifecycleOwner, stateObserver)
        cam?.cameraInfo?.zoomState?.observe(lifecycleOwner, zoomObserver)
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        onDispose {
            cam?.cameraInfo?.cameraState?.removeObserver(stateObserver)
            cam?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
            previewView.previewStreamState.removeObserver(streamObserver)
            runCatching { prov.unbind(preview, capture) }
            camera = null
            imageCapture = null
            zoom = null
        }
    }
    val hasFlash = remember(camera) { camera?.cameraInfo?.hasFlashUnit() == true }
    LaunchedEffect(imageCapture, flashMode) { imageCapture?.flashMode = flashMode }
    LaunchedEffect(imageCapture, deviceRotation) { imageCapture?.targetRotation = deviceRotation }

    // Device orientation: the capture's EXIF turn and the control glyph rotation (iOS
    // CameraControlOrientation), independent of whether the Activity rotates.
    DisposableEffect(ctx) {
        val listener = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceRotation = rotationForOrientation(orientation, deviceRotation)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
    // Accumulated (unwrapped) so a 270 -> 0 turn animates the short way round.
    val unwrapped = remember { FloatArray(1) }
    val wantedRotation = (quarterDegrees(deviceRotation) - quarterDegrees(displayRotation)).toFloat()
    val controlTarget = remember(wantedRotation) {
        val current = unwrapped[0]
        val delta = Math.IEEEremainder((wantedRotation - current).toDouble(), 360.0).toFloat()
        (current + delta).also { unwrapped[0] = it }
    }
    val controlRotation by animateFloatAsState(
        controlTarget,
        animationSpec = if (reduceMotion) tween(0) else SpMotion.easeOut(220),
        label = "controlRotation",
    )

    // --- shots -------------------------------------------------------------------------------
    val shots by SpCameraDevelop.shots.collectAsStateWithLifecycle()
    val latest = shots.lastOrNull()
    val developing = shots.count { it.phase == SpShotPhase.WAITING || it.phase == SpShotPhase.DEVELOPING }
    val exportProgress by ExportWorkRuntime.progress.collectAsStateWithLifecycle()
    var capturing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTick by remember { mutableIntStateOf(0) }
    fun say(text: String) { message = text; messageTick++ }
    LaunchedEffect(messageTick) {
        if (message != null) { delay(2200); message = null }
    }
    // A shot that develops while the camera is open announces itself once.
    val seenDeveloping = remember { HashSet<Long>() }
    LaunchedEffect(latest?.id, latest?.phase) {
        val shot = latest ?: return@LaunchedEffect
        when (shot.phase) {
            SpShotPhase.WAITING, SpShotPhase.DEVELOPING -> seenDeveloping += shot.id
            SpShotPhase.DONE -> if (seenDeveloping.remove(shot.id)) say(app.getString(R.string.spcam_saved))
            SpShotPhase.FAILED -> if (seenDeveloping.remove(shot.id)) say(app.getString(R.string.spcam_develop_failed))
        }
    }

    // --- shutter -----------------------------------------------------------------------------
    val captureHandler = remember { Handler(Looper.getMainLooper()) }
    val shutterSound = remember { MediaActionSound().apply { runCatching { load(MediaActionSound.SHUTTER_CLICK) } } }
    DisposableEffect(Unit) { onDispose { runCatching { shutterSound.release() } } }
    val blink = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    var flyImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var finderCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var thumbCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var flyFrom by remember { mutableStateOf(Rect.Zero) }
    var flyTo by remember { mutableStateOf(Rect.Zero) }
    val ready = imageCapture != null && !capturing && !cameraIssue

    fun shoot() {
        val capture = imageCapture ?: return
        if (capturing || cameraIssue) return
        capturing = true
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        // The latent image: the framed viewfinder at the instant of the shutter.
        val latent = runCatching { previewView.bitmap?.let { scaleToEdge(it, 384) } }.getOrNull()
        val recipe = SpCameraRecipe(filmId, metering, exposureEv)
        val rawCapture = capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG
        // iOS captureFormat: Bayer RAW only at 1x; a DNG never carries the zoom crop.
        val developRaw = rawCapture && zoom?.zoomRatio == 1f
        val target = runCatching { SpCameraStorage.newCapture(app) }.getOrNull()
        if (target == null) {
            capturing = false
            say(app.getString(R.string.spcam_capture_failed))
            return
        }
        scope.launch {
            if (!reduceMotion) {
                blink.snapTo(0.85f)
                blink.animateTo(0f, tween(260, easing = com.argentroom.app.silverphase.SpMotion.EaseOut))
            }
        }
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = useFront }
        val options = ImageCapture.OutputFileOptions.Builder(target.jpeg).setMetadata(metadata).build()
        val result = SpCameraCaptureResult(expectsDng = rawCapture, useDng = developRaw)
        var watchdog: Runnable? = null
        fun finish(input: Input?) {
            if (input == null) return
            watchdog?.let(captureHandler::removeCallbacks)
            capturing = false
            if (input == Input.FAILED) {
                target.discard()
                latent?.recycle()
                say(app.getString(R.string.spcam_capture_failed))
                return
            }
            if (input == Input.JPEG) target.dng.delete()
            val queued = runCatching { SpCameraDevelop.submit(app, target, recipe, latent, input == Input.RAW) }
            if (queued.isFailure) {
                latent?.recycle()
                Diag.w("camera queue failed: ${queued.exceptionOrNull()?.javaClass?.simpleName}")
                say(app.getString(R.string.spcam_develop_failed))
                return
            }
            val root = rootCoords
            val from = finderCoords
            val to = thumbCoords
            if (!reduceMotion && latent != null && root != null && from != null && to != null &&
                root.isAttached && from.isAttached && to.isAttached
            ) {
                flyFrom = root.localBoundingBoxOf(from)
                flyTo = root.localBoundingBoxOf(to)
                flyImage = latent.asImageBitmap()
                scope.launch {
                    fly.snapTo(0f)
                    fly.animateTo(1f, SpMotion.easeInOut(340))
                    flyImage = null
                }
            }
        }
        // Every callback and the watchdog run on the main thread, even if the camera UI closes.
        // Late writes can only refer to this shot's isolated staging files.
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val (format, file) = when (outputFileResults.imageFormat) {
                    ImageFormat.JPEG -> SavedImage.JPEG to target.jpeg
                    ImageFormat.RAW_SENSOR -> SavedImage.DNG to target.dng
                    else -> return
                }
                val finished = result.input
                if (finished != null) {
                    if (finished == Input.FAILED || (finished == Input.JPEG && format == SavedImage.DNG)) file.delete()
                    return
                }
                finish(if (file.length() > 0L) result.saved(format) else result.error())
            }

            override fun onError(exception: ImageCaptureException) {
                Diag.w("camera capture error ${exception.imageCaptureError}: ${exception.javaClass.simpleName}")
                finish(result.error())
            }
        }
        watchdog = Runnable { finish(result.timeout()) }.also {
            captureHandler.postDelayed(it, CAPTURE_CALLBACK_TIMEOUT_MS)
        }
        runCatching {
            if (rawCapture) {
                val rawOptions = ImageCapture.OutputFileOptions.Builder(target.dng).setMetadata(metadata).build()
                capture.takePicture(rawOptions, options, ContextCompat.getMainExecutor(ctx), callback)
            } else {
                capture.takePicture(options, ContextCompat.getMainExecutor(ctx), callback)
            }
        }.onFailure { finish(result.timeout()) }
    }

    // --- focus / exposure / zoom -------------------------------------------------------------
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusLocked by remember { mutableStateOf(false) }
    var focusTick by remember { mutableIntStateOf(0) }
    var draggingEv by remember { mutableStateOf(false) }
    fun focusAt(point: Offset, lock: Boolean) {
        val cam = camera ?: return
        runCatching {
            val meteringPoint = previewView.meteringPointFactory.createPoint(point.x, point.y)
            val action = FocusMeteringAction.Builder(
                meteringPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
            ).apply {
                if (lock) disableAutoCancel() else setAutoCancelDuration(4, TimeUnit.SECONDS)
            }.build()
            cam.cameraControl.startFocusAndMetering(action)
        }
        focusPoint = point
        focusLocked = lock
        focusTick++
        if (lock) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    fun zoomTo(ratio: Float) {
        val z = zoom ?: return
        runCatching { camera?.cameraControl?.setZoomRatio(ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio)) }
    }

    // Volume keys are the hardware shutter (iOS: hardware shutter on the preview).
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoords = it }
            .focusRequester(keyFocus)
            .focusable()
            .onKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent.KEYCODE_CAMERA,
                    -> {
                        if (event.type == KeyEventType.KeyUp && ready) shoot()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Top: back + finder menus (MobileCameraView.topControls).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpRoundGlass {
                    SpChromeButton(
                        stringResource(R.string.spcam_back), SpGlyph.BACK,
                        modifier = Modifier.rotate(controlRotation), enabled = !capturing, onClick = onClose,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.spChromeSurface(radius = 24.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashButton(flashMode, enabled = hasFlash && !capturing, rotation = controlRotation) {
                        flashMode = nextFlash(flashMode)
                        prefs.flash = flashMode
                        SpHaptics.select(view)
                    }
                    MeteringMenu(metering, enabled = !capturing, rotation = controlRotation) {
                        metering = it
                        prefs.metering = it
                    }
                    CompositionMenu(
                        grid = grid, level = showLevel, enabled = !capturing, rotation = controlRotation,
                        onGrid = { grid = it; prefs.grid = it },
                        onLevel = { showLevel = it; prefs.level = it },
                        raw = if (rawAvailable) prefersRaw else null,
                        onRaw = { prefersRaw = it; prefs.prefersRaw = it },
                    )
                }
            }
            // The honest status line (MobileCameraView.previewStatus).
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                val developingShot = latest?.takeIf { it.phase == SpShotPhase.DEVELOPING }
                val status = when {
                    // The batch export path reports no stages, so the runtime's fraction usually
                    // stays 0 for a develop: show a percentage only when there is a real one.
                    developingShot != null && exportProgress > 0f ->
                        stringResource(R.string.spcam_developing_progress, (exportProgress * 100).toInt().coerceIn(0, 99)) +
                            " · " + developingShot.filmName
                    developingShot != null ->
                        stringResource(R.string.spcam_developing) + " " + developingShot.filmName
                    developing > 0 -> stringResource(R.string.spcam_developing)
                    else -> stringResource(R.string.spcam_preview_raw)
                }
                SpPill(status)
                if (exposureEv != 0f && !draggingEv) {
                    Spacer(Modifier.width(6.dp))
                    SpPill(
                        stringResource(R.string.spcam_ev_badge, formatEv(exposureEv)),
                        accent = true,
                        onClick = { exposureEv = 0f },
                        description = stringResource(R.string.spcam_ev_reset),
                    )
                }
            }
            // Viewfinder.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .aspectRatio(if (portraitDisplay) 3f / 4f else 4f / 3f, matchHeightConstraintsFirst = !portraitDisplay)
                        .clip(RoundedCornerShape(2.dp))
                        .onGloballyPositioned { finderCoords = it }
                        .finderGestures(
                            enabled = { camera != null && !capturing },
                            onTap = { focusAt(it, lock = false) },
                            onLongPress = { focusAt(it, lock = true) },
                            onDrag = { dy ->
                                draggingEv = true
                                val stops = -dy / with(density) { 90.dp.toPx() }
                                val next = (exposureEv + stops).coerceIn(SpCameraDevelop.EV_MIN, SpCameraDevelop.EV_MAX)
                                val snapped = (next * 10f).let { kotlin.math.round(it) } / 10f
                                if (snapped != exposureEv) {
                                    if (snapped == 0f || (snapped * 10f).toInt() % 10 == 0) SpHaptics.tick(view, major = true)
                                    exposureEv = snapped
                                }
                            },
                            onDragEnd = { draggingEv = false },
                            onZoom = { factor -> zoom?.let { zoomTo(it.zoomRatio * factor) } },
                        ),
                ) {
                    if (!noLens) {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    }
                    if (streaming && !noLens) {
                        GridOverlay(grid)
                        if (showLevel) LevelOverlay(controlRotation)
                        FocusOverlay(focusPoint, focusLocked, focusTick, draggingEv, exposureEv, controlRotation, reduceMotion)
                    }
                    when {
                        noLens -> SpCameraNotice(title = stringResource(R.string.spcam_title), body = stringResource(R.string.spcam_unavailable))
                        cameraIssue -> SpCameraNotice(
                            title = stringResource(R.string.spcam_title),
                            body = stringResource(R.string.spcam_camera_error),
                            action = stringResource(R.string.spcam_retry),
                            onAction = { retry++ },
                        )
                        !streaming -> Text(
                            stringResource(R.string.spcam_opening),
                            style = SpDesign.label, color = SpStyle.secondaryText,
                            modifier = Modifier.align(Alignment.Center).rotate(controlRotation),
                        )
                    }
                    // Shutter blink.
                    if (blink.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = blink.value }.background(SpStyle.ink))
                }
            }
            // Finder row: flip | zoom chips | balance (MobileCameraView.finderControls).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpRoundGlass {
                    SpCamButton(
                        title = stringResource(if (useFront) R.string.spcam_flip_to_back else R.string.spcam_flip_to_front),
                        glyph = SpCamGlyph.FLIP,
                        enabled = hasFront && hasBack && !capturing && camera != null,
                        rotation = controlRotation,
                    ) {
                        wantFront = !useFront
                        prefs.front = wantFront
                        focusPoint = null
                        focusLocked = false
                        SpHaptics.select(view)
                    }
                }
                Spacer(Modifier.weight(1f))
                ZoomChips(zoom, enabled = !capturing, rotation = controlRotation) { zoomTo(it); SpHaptics.select(view) }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(SpDesign.hitSize))
            }
            // Film strip (iOS opens a sheet grid; the Android port keeps the viewfinder visible).
            var filmsOpen by remember { mutableStateOf(false) }
            AnimatedVisibility(
                visible = filmsOpen,
                enter = if (reduceMotion) fadeIn(tween(SpMotion.REDUCED_FADE_MS)) else expandVertically(SpMotion.smooth028()) + fadeIn(SpMotion.smooth028()),
                exit = if (reduceMotion) fadeOut(tween(SpMotion.REDUCED_FADE_MS)) else shrinkVertically(SpMotion.smooth028()) + fadeOut(SpMotion.smooth028()),
            ) {
                FilmStrip(films, filmId) { stock ->
                    filmId = stock.id
                    prefs.film = stock.id
                    SpHaptics.select(view)
                }
            }
            // Capture dock.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    LatestShot(
                        shot = latest,
                        waiting = developing,
                        progress = if (latest?.phase == SpShotPhase.DEVELOPING) exportProgress else null,
                        rotation = controlRotation,
                        modifier = Modifier.onGloballyPositioned { thumbCoords = it },
                    ) {
                        val shot = latest ?: return@LatestShot
                        when (shot.phase) {
                            SpShotPhase.DONE, SpShotPhase.FAILED -> onOpenInEditor(shot.original)
                            else -> say(app.getString(R.string.spcam_latest_developing))
                        }
                    }
                }
                Shutter(enabled = ready && films.isNotEmpty(), busy = capturing, onClick = ::shoot)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    FilmButton(
                        name = films.firstOrNull { it.id == filmId }?.name ?: stringResource(R.string.spcam_film_label),
                        open = filmsOpen,
                        enabled = films.isNotEmpty() && !capturing,
                        rotation = controlRotation,
                    ) { filmsOpen = !filmsOpen }
                }
            }
        }

        // Transient message (develop done / failed / capture failed).
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(160, easing = com.argentroom.app.silverphase.SpMotion.EaseOut)),
            exit = fadeOut(tween(200, easing = com.argentroom.app.silverphase.SpMotion.EaseOut)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                message.orEmpty(),
                style = SpDesign.noticeTitle,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .rotate(controlRotation)
                    .spChromeSurface(radius = 20.dp)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .widthIn(max = 260.dp),
            )
        }

        // The latent frame flying into the thumbnail (iOS landReveal).
        val flying = flyImage
        if (flying != null && flyFrom.width > 0f && flyTo.width > 0f) {
            val t = fly.value
            val toScale = flyTo.width / flyFrom.width
            val s = 1f + (toScale - 1f) * t
            val cx = flyFrom.center.x + (flyTo.center.x - flyFrom.center.x) * t
            val cy = flyFrom.center.y + (flyTo.center.y - flyFrom.center.y) * t
            with(density) {
                Image(
                    flying, null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(flyFrom.width.toDp(), flyFrom.height.toDp())
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = s
                            scaleY = s
                            translationX = cx - flyFrom.width * s / 2f
                            translationY = cy - flyFrom.height * s / 2f
                            alpha = 1f - 0.35f * t
                            shape = RoundedCornerShape((24f * t).dp)
                            clip = true
                        },
                )
            }
        }
    }
}
