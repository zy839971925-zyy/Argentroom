/*
 * Spektrafilm for Android — the editor screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * EditorScreen and its top bar / preview region, moved out of MainActivity.kt (#273). They are
 * MainActivity extensions for now so the move changes no behaviour; later #273 slices split
 * them into stateless per-panel composables.
 */
package com.spectrafilm.app.editor

import com.spectrafilm.app.R
import com.spectrafilm.app.*
import com.spectrafilm.app.diagnostics.*
import com.spectrafilm.app.export.*
import com.spectrafilm.app.library.*
import com.spectrafilm.app.look.*
import com.spectrafilm.app.render.*
import com.spectrafilm.app.settings.*
import com.spectrafilm.app.source.*
import com.spectrafilm.app.ui.*
import androidx.compose.runtime.mutableIntStateOf

import kotlinx.coroutines.flow.distinctUntilChanged

import androidx.compose.runtime.snapshotFlow

import android.content.Intent
import androidx.activity.OnBackPressedDispatcher
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import com.spectrafilm.app.silverphase.SpProcessControls
import com.spectrafilm.app.silverphase.spHoldToCompare
import com.spectrafilm.app.silverphase.spS
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.app.masks.hasSemantic
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val OPEN_PLACEHOLDER_FADE_MS = 220
/**
 * The editor screen. It used to be a MainActivity extension; everything it took from the
 * Activity is now a parameter, passed by [AppRoot] (#452):
 *  - [dispatchers] and the editor's ViewModels (Activity-scoped, see MainActivity);
 *  - [incomingImageIntent], the unconsumed VIEW/SEND intent (#162);
 *  - [launcherShortcut], the unconsumed launcher shortcut (#467);
 *  - [setEditorShortcutHandler], which installs or clears the hardware keyboard handler;
 *  - [heldDropPermissions] and [requestDragAndDropPermissions] for cross-app drops;
 *  - [lifecycle], the Activity lifecycle whose ON_STOP checkpoints the session, and
 *    [onBackPressedDispatcher] for the Esc shortcut.
 */
@Composable
internal fun EditorScreen(
    dispatchers: AppDispatchers,
    editorVm: EditorViewModel,
    exportVm: ExportViewModel,
    exportPresetVm: ExportPresetViewModel,
    filmPreviewVm: StockPreviewViewModel,
    printPreviewVm: StockPreviewViewModel,
    presetPreviewVm: StockPreviewViewModel,
    presetVm: PresetViewModel,
    toolVm: EditorToolViewModel,
    versionsVm: VersionsViewModel,
    viewerVm: EditorViewerViewModel,
    incomingImageIntent: MutableState<Intent?>,
    launcherShortcut: MutableState<LauncherShortcut?>,
    setEditorShortcutHandler: (((EditorShortcut) -> Boolean)?) -> Unit,
    heldDropPermissions: MutableList<android.view.DragAndDropPermissions>,
    requestDragAndDropPermissions: (android.view.DragEvent) -> android.view.DragAndDropPermissions?,
    lifecycle: Lifecycle,
    onBackPressedDispatcher: OnBackPressedDispatcher,
    settings: AppSettings,
    restoration: EditorRestoration,
    sessionRestoreNotice: String?,
    onSessionRestoreNoticeConsumed: () -> Unit,
    onSessionCheckpoint: (EditorSessionDocument, liveMutation: Boolean) -> Boolean,
    activeCategoryState: MutableState<Category?>,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLibrary: () -> Unit,
    onProfileGroups: (List<DropdownGroup>, List<DropdownGroup>) -> Unit,
    onOpenFilmCurves: (id: String, name: String) -> Unit,
    onOpenPrintCurves: (id: String, name: String) -> Unit,
    onSettingsAction: (SettingsAction) -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    // Editor-local UI waiters are cancelled when this destination leaves composition. Durable
    // source/recipe/export work is submitted to its process owner first; AppRoot reconciles
    // SourceAccess again before a later editor composition, so disposed state is never mutated.
    val scope = rememberCoroutineScope()
    val editorInstance = remember { editorProbe.beginLiveEditorInstance() }
    // Parent checkpoints update while this composition is alive. Restoration is intentionally
    // one-shot; the newest checkpoint becomes the initializer only after navigation creates a
    // fresh EditorScreen composition.
    val initialRestoration = remember { restoration }
    val initialRestoredSession = initialRestoration.document
    val initialSessionRestoreNotice = remember { sessionRestoreNotice }
    SideEffect {
        if (sessionRestoreNotice != null) onSessionRestoreNoticeConsumed()
    }

    // Per-composition editing state, owned by EditorViewModel and fresh for every composition
    // (see EditorViewModel for why): params, manual rotation, undo/redo history.
    val session = remember { editorVm.beginSession(initialRestoration) }
    // The catalog this composition has applied (null until its restore ran, #452). Engine,
    // profiles and built-in looks are read-only views of that one value, not separate copies.
    val appliedCatalog = session.appliedCatalog
    val engineState = remember { derivedStateOf { appliedCatalog.value?.engine } }
    val engine by engineState
    val profilesState = remember { derivedStateOf { appliedCatalog.value?.profiles.orEmpty() } }
    val profiles by profilesState
    val state = session.params

    // Plain state holders and per-composition caches (EditorScreenState.kt).
    val screen = rememberEditorScreenState()
    val sourceCache = screen.sourceCache
    val gradeCache = screen.gradeCache
    val zoomSourceCache = screen.zoomSourceCache
    val zoomDecodeFlight = screen.zoomDecodeFlight
    val previewDecodeFlight = screen.previewDecodeFlight


    // bundled catalog (friendly stock names + grouping) and built-in presets
    val builtInGroups by remember {
        derivedStateOf { appliedCatalog.value?.builtInGroups.orEmpty() }
    }
    val catalogReady by remember { derivedStateOf { appliedCatalog.value != null } }

    // SourceAccess and the complete session are reconciled before this Composable exists.
    // Keep the resulting identity as ordinary live state: destination SavedState is never a
    // second restoration authority and therefore cannot resurrect an older subject.
    // The visible photo is one immutable EditorSourceState on the session (#452), seeded from
    // initialRestoration.source; these are read-only views of it that stay current inside
    // callbacks. Every change goes through a session transition (showSource / showDemo /
    // requireSourceAuthorization), so uri, kind, name and authorization move together.
    val sourceState = session.source
    val sourceUriState = remember { derivedStateOf { sourceState.value.uri?.let(Uri::parse) } }
    val sourceUri by sourceUriState
    val sourceKindState = remember { derivedStateOf { sourceState.value.kind } }
    val sourceKind by sourceKindState
    val sourceNameState = remember { derivedStateOf { sourceState.value.displayName } }
    val sourceName by sourceNameState
    val sourceRuntime = remember(ctx.applicationContext) { ctx.appContainer.sources }
    val sourceAccess = sourceRuntime.coordinator
    val sourceMutationGate = sourceRuntime.mutations
    val sourceAuthorizationRequired by remember {
        derivedStateOf { sourceState.value.authorizationRequired }
    }
    val sourceRenderAllowed = !sourceAuthorizationRequired
    val exportSourceIdentity = remember(
        sourceUri,
        sourceKind,
        sourceAuthorizationRequired,
    ) {
        ExportSourceIdentityAuthority.bind(
            uri = sourceUri?.toString(),
            kind = sourceKind,
            authorizationRequired = sourceAuthorizationRequired,
        )
    }
    val previewState = screen.previewState
    var preview by previewState
    // Time to full display (MAD audit fix 4): the editor is fully drawn once the first
    // rendered preview is on screen, so startup can be measured as TTFD.
    ReportDrawnWhen { preview != null }
    val beforePreviewState = screen.beforePreviewState
    var beforePreview by beforePreviewState
    // The histogram captures its pixels during composition and never retains either Bitmap.
    // Once a frame is replaced (or the editor leaves composition), no asynchronous reader can
    // still touch it, so release the native pixel storage deterministically after composition.
    DisposableEffect(preview) {
        val owned = preview
        onDispose { owned?.let(::retirePreviewBitmap) }
    }
    DisposableEffect(beforePreview) {
        val owned = beforePreview
        onDispose { owned?.let(::retirePreviewBitmap) }
    }
    val previewBusyState = screen.previewBusyState
    var previewBusy by previewBusyState
    val decodingState = screen.decodingState
    var decoding by decodingState
    val lastRenderMsState = screen.lastRenderMsState
    var lastRenderMs by lastRenderMsState
    val renderErrState = screen.renderErrState
    var renderErr by renderErrState
    // Seed the export sheet and phase machine from the checkpoint once (EditorExportRestore.kt).
    val initialEditorStatus = rememberEditorExportRestore(
        exportVm = exportVm,
        settings = settings,
        restoredExport = initialRestoredSession?.export,
        initialSessionRestoreNotice = initialSessionRestoreNotice,
        exportSourceIdentity = exportSourceIdentity,
    )
    val statusState = remember { mutableStateOf(initialEditorStatus) }
    var status by statusState
    // Composition reads (UI and effect keys) use this collected copy. Effects and callbacks
    // read exportVm.current: the collected copy trails a write by one dispatch, and a
    // checkpoint taken right after an action must journal the new state.
    //
    // #270: this function's own body must NOT read `exportUi` directly. A read here
    // subscribes the whole editor to every export-state change (opening the sheet, each
    // option edit, the Export tap), and each one then recomposes this entire screen -- the
    // 37-63 ms recomposes the device trace measured. The body reads the derived fields
    // below, which only invalidate when that one field changes; the export sheet reads
    // `exportUiState` inside its own recompose scope (EditorExportSheetHost).
    val exportUiState = exportVm.uiState.collectAsStateWithLifecycle()
    // #161: busy/done are DERIVED from the one export state machine, never parallel
    // flags — the pill can no longer claim "Exporting…" over a finished export, and no
    // path can forget to clear a boolean the phase transition already implies.
    val exportInFlight by remember(exportUiState) { derivedStateOf { exportUiState.value.inFlight } }
    val exportDone by remember(exportUiState) { derivedStateOf { exportUiState.value.done } }
    val exportLastShare by remember(exportUiState) { derivedStateOf { exportUiState.value.lastShare } }
    val defaultExportFrame = settings.exportFrameSpec
    val exportOptions by remember(exportUiState, defaultExportFrame) {
        derivedStateOf { exportUiState.value.options.resolveFrameDefault(defaultExportFrame) }
    }
    // Only the render is modal; the result is a Snackbar and the pill's check, which never
    // block the editor.
    val exportMaskVisible = exportInFlight
    val previewTickState = screen.previewTickState
    var previewTick by previewTickState
    val publicationGate = screen.publicationGate
    val previewRevision = remember(previewTick) { publicationGate.nextRevision() }
    // Slider drag tracking, the HUD readout and the sideways photo scrub (EditorSliderTracking.kt).
    val sliderTracking = rememberEditorSliderTracking(viewerVm)
    val interacting = sliderTracking.interacting
    val sliderReadout = sliderTracking.sliderReadout
    val lastControlAlive = sliderTracking.lastControlAlive
    val scrubArmed = sliderTracking.scrubArmed
    val sliderInteraction = sliderTracking.sliderInteraction
    val photoScrub = sliderTracking.photoScrub

    // source rotation (applied to the decoded LinearImage -> preview AND export).
    // This is the user's MANUAL step only; the EXIF baseline is derived fresh per load
    // (see loadSource) and is NOT persisted in the recipe.
    val rotationState = session.rotation
    var rotation by rotationState

    // Open placeholder, swipe to the neighbouring photo and the folder strip (EditorPhotoBrowse.kt).
    val photoBrowse = rememberEditorPhotoBrowse(
        ctx = ctx,
        dispatchers = dispatchers,
        settings = settings,
        profiles = profiles,
        engine = engine,
        state = state,
        sourceUri = sourceUri,
        sourceKind = sourceKind,
        sourceAuthorizationRequired = sourceAuthorizationRequired,
        rotationState = rotationState,
        previewState = previewState,
        previewBusyState = previewBusyState,
        interacting = interacting,
        previewTick = previewTick,
        incomingImageIntent = incomingImageIntent,
    )
    val openPlaceholder = photoBrowse.openPlaceholder
    val photoSwipe = photoBrowse.photoSwipe
    val folderStrip = photoBrowse.folderStrip

    val dngFallbackNoticeState = screen.dngFallbackNoticeState
    var dngFallbackNotice by dngFallbackNoticeState

    // LUT export
    val bakingLutState = screen.bakingLutState
    var bakingLut by bakingLutState
    val pendingLutTextState = screen.pendingLutTextState
    var pendingLutText by pendingLutTextState

    // Viewer modes (compare, histogram, hidden chrome) live in EditorViewerViewModel (#272).
    // Not journalled: every editor composition starts with them off, as before.
    remember { viewerVm.reset(); true }
    val viewerUi by viewerVm.uiState.collectAsStateWithLifecycle()

    // Experimental GPU LUT preview (Settings → Experimental, default OFF). When on we
    // keep the latest linear proxy + a baked 3D LUT of the current look; PreviewRegion
    // GPU-samples them instead of the CPU bitmap. Read at composition — toggling in
    // Settings applies on the next return to the editor.
    val gpuEnabled = settings.gpuPreview && !state.filmEdge.spatial
    val gpuProxyLeaseState = screen.gpuProxyLeaseState
    var gpuProxyLease by gpuProxyLeaseState
    val gpuProxy = gpuProxyLease?.image
    val gpuLutState = screen.gpuLutState
    var gpuLut by gpuLutState
    val gpuGainState = screen.gpuGainState
    var gpuGain by gpuGainState

    // interactive crop overlay (Lightroom-style); hosts on top of everything.
    val restoredTool = initialRestoredSession?.tool
    val restoredOverlay = restorableEditorOverlay(
        restoredTool?.overlay ?: EditorOverlayTool.NONE,
    )
    // Crop, mask-geometry editor, eyedropper (WB gray point / mask colour / mask luminance)
    // and the selected mask live in EditorToolViewModel (#272), seeded once per editor
    // composition from the checkpoint. Composition reads use toolUi; effects and
    // callbacks read toolVm.current.
    remember { toolVm.restore(restoredOverlay, restoredTool?.maskIndex ?: 0); true }
    val toolUi by toolVm.uiState.collectAsStateWithLifecycle()

    // The Crop tab's in-progress draft (CropTool.kt), published from the layout below. Leaving
    // the tab writes it to the recipe in one go, so the history records ONE undo step; an
    // untouched draft writes nothing. Every way out of the tab goes through here.
    val cropDraftRef = remember { mutableStateOf<CropDraft?>(null) }
    // Bumped to throw the pending draft away (Undo while it holds an edit): a key of the draft.
    var cropDraftEpoch by remember { mutableIntStateOf(0) }
    // Write a touched draft to the recipe. A saved export no longer shows the photo, so its
    // result is dropped right here: Share straight after must not send the old file (the
    // snapshot watcher that normally does this only runs a frame later).
    fun commitCropDraft() {
        val c = cropDraftRef.value?.takeIf { it.touched }?.commit() ?: return
        state.crop = c.crop
        state.cropCenter = c.center
        state.cropSize = c.size
        state.straightenDegrees = c.straighten
        cropDraftRef.value = null
        if (exportVm.current.done) exportVm.onAction(ExportAction.DismissResult)
    }
    fun closeCrop() {
        if (!toolVm.current.cropOpen) return
        commitCropDraft()
        cropDraftRef.value = null
        toolVm.onAction(EditorToolAction.CloseCrop)
    }
    // An edit still only in the Crop tab's draft: Undo takes that back before the history.
    fun discardCropDraft(): Boolean {
        if (cropDraftRef.value?.touched != true) return false
        cropDraftRef.value = null
        cropDraftEpoch++
        return true
    }
    // The Crop item in the bottom bar / rail: open the tab, or leave it when it is open.
    fun toggleCrop() {
        if (toolVm.current.cropOpen) closeCrop() else toolVm.onAction(EditorToolAction.OpenCrop)
    }

    // Lightroom's single tap on the photo: everything but the picture gets out of the way
    // (viewerUi.chromeHidden). Deliberately NOT animated. Hiding the top bar and the rail
    // changes the preview's height, and the preview is a weight(1f) sibling — animating
    // that would remeasure it every frame, which is the churn documented on PanelOverlay.
    // A one-shot layout change is a different thing and is what this is.
    // 100% grain magnifier + Lightroom-style ROI zoom patch (EditorZoom.kt).
    val zoom = rememberEditorZoom(
        ctx = ctx,
        scope = scope,
        dispatchers = dispatchers,
        state = state,
        engineState = engineState,
        previewTickState = previewTickState,
        sourceRenderAllowed = sourceRenderAllowed,
        patchSuppressed = { toolVm.current.anyOverlayOpen || viewerVm.current.compare },
    )
    val magnifierOpen by zoom.magnifierOpenState
    val roiOverlay by zoom.roiOverlayState
    val zoomPrewarmEligible = zoom.zoomPrewarmEligible && !state.filmEdge.spatial
    fun closeMagnifier() = zoom.closeMagnifier()

    /** Retire every source-derived resource before committing a different source identity. */
    fun retireSourceResources() = screen.retireSourceResources(zoom)

    // Authorization is part of the render identity. Revocation of the same URI must retire
    // cached/native work just as aggressively as replacing the URI, and no render effect is
    // allowed to restart until a successful SourceAccess mutation clears this gate.
    LaunchedEffect(sourceRenderAllowed) {
        if (!sourceRenderAllowed) retireSourceResources()
    }

    // Invalidate an old edit generation before its next main-thread publication, cancel its
    // remaining native work, and retire its overlay while the replacement preview is pending.
    LaunchedEffect(previewTick) { zoom.onEdit(previewTick) }

    // presets
    val restoredPreset = initialRestoredSession?.preset
    val presetListState = screen.presetListState
    var presetList by presetListState
    // Preset panel selection + name, the Lightroom-style amount blend anchors and the
    // copy/paste settings clipboard (backlog #10) live in PresetViewModel (#272), seeded
    // once per editor composition from the checkpoint. The clipboard is part of the
    // bounded editor-session document, so navigation/recreation does not silently erase a
    // deliberate Copy action; no pixels or source metadata are included.
    remember { presetVm.restore(restoredPreset); true }
    // Composition reads use the collected copy; effects and callbacks read presetVm.current.
    val presetUi by presetVm.uiState.collectAsStateWithLifecycle()

    // Built-in presets are SPARSE overlays: each authors only the fields it means to
    // set and deliberately leaves everything else alone, so tapping one after another
    // COMPOUNDS them. enlarger.paperGrade is authored only by Tri-X 400, so a colour look
    // tapped after it would silently keep grade 3 (and any field a future look authors
    // alone would leak the same way).
    //
    // So remember the look a browsing run STARTED from and apply each built-in onto that
    // rather than onto the compounded result. There is no list of clear-sites to keep in
    // sync: a run ends by itself the moment the live state stops matching what the last
    // apply produced, which covers source changes, resets, pastes and any hand edit in
    // one condition. (A hand-maintained effect-key list in this file has drifted before.)
    val presetBaselineJsonState = screen.presetBaselineJsonState
    var presetBaselineJson by presetBaselineJsonState
    val lastBuiltInOutputState = screen.lastBuiltInOutputState
    var lastBuiltInOutput by lastBuiltInOutputState
    // Id of the built-in look whose result [lastBuiltInOutput] is: the Look strip's selection.
    val lastBuiltInIdState = screen.lastBuiltInIdState

    // Capture the pre-apply look, run [apply], then snapshot the full preset and arm
    // the amount slider at 100%. Used by both built-in and saved-preset apply paths.
    // [baseOverride] pins the amount slider's 0% end to a caller-chosen look — the
    // built-in path needs it because it restores a baseline INSIDE [apply], so the live
    // state just before the call is the wrong anchor.
    fun applyWithAmount(baseOverride: String? = null, apply: () -> Unit) {
        val base = baseOverride ?: runCatching { Presets.toJsonString(state) }.getOrNull()
        apply()
        val full = runCatching { Presets.toJsonString(state) }.getOrNull()
        if (base != null && full != null) {
            presetVm.onAction(PresetAction.Applied(base, full))
        }
    }

    // active adjustment category (null = panel closed); hoisted to AppRoot so it
    // survives navigation to Settings/About and back.
    var activeCategory by activeCategoryState
    // The Crop tab replaces whatever panel was open, and opening or leaving it re-renders the
    // preview: while it is open the preview is drawn without the engine crop
    // (EditorPreviewEffects), so the crop frame can sit on the whole photo.
    val cropOpenNow = toolUi.cropOpen
    var cropOpenSeen by remember { mutableStateOf(cropOpenNow) }
    LaunchedEffect(cropOpenNow) {
        if (cropOpenNow) activeCategory = null
        if (cropOpenNow != cropOpenSeen) {
            cropOpenSeen = cropOpenNow
            previewTick++
        }
    }
    // Opening any other tab leaves the Crop tab first, keeping its draft.
    val openCategory: (Category?) -> Unit = { category ->
        if (category != null) closeCrop()
        activeCategory = category
    }
    // 0..1 while a predictive-back swipe is closing the open panel; drives its shrink.
    var panelBackProgress by screen.panelBackProgressState

    val presetRepo = remember(ctx) { ctx.appContainer.presets }
    fun refreshPresets() { presetList = presetRepo.list() }

    // --- non-destructive recipe (sidecar) layer ---
    val recipeReadyState = screen.recipeReadyState
    var recipeReady by recipeReadyState
    val hasRecipeState = screen.hasRecipeState
    var hasRecipe by hasRecipeState
    val defaultsJsonState = screen.defaultsJsonState
    var defaultsJson by defaultsJsonState
    LaunchedEffect(recipeReady, state.localAdjustments.size) {
        if (recipeReady) {
            toolVm.onAction(EditorToolAction.ClampMask(state.localAdjustments.size))
        }
    }
    val snackbarHost = screen.snackbarHost
    val recipeRepo = remember(ctx) { ctx.appContainer.recipes }
    val recipeKey = remember(sourceUri) { Recipes.keyFor(sourceUri) }
    // Lifecycle-aware like every other collector here, but kept active down to CREATED on
    // purpose: an export that finishes while the app is in the background must still be
    // claimed by this editor. ExportWorkRuntime retains a Finished outcome for only five
    // minutes, so pausing at STARTED (the default) could let it expire unseen.
    val exportRuntimeStateState = ExportWorkRuntime.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.CREATED,
    )
    val exportRuntimeState by exportRuntimeStateState
    val lastHandledExportRunState = screen.lastHandledExportRunState
    var lastHandledExportRun by lastHandledExportRunState
    val exportPublicationState = screen.exportPublicationState
    var exportPublication by exportPublicationState
    val sessionCheckpointAction = screen.sessionCheckpointAction

    // The process-owned export survives this Activity. A recreated UI observes exactly one
    // retained terminal outcome instead of turning a committed MediaStore row into CANCELLED.
    LaunchedEffect(
        exportRuntimeState,
        exportSourceIdentity,
        recipeReady,
        onSessionCheckpoint,
    ) {
        runExportRuntimeObserver(
            dispatchers,
            exportVm,
            ctx = ctx,
            exportSourceIdentity = exportSourceIdentity,
            screen = screen,
            exportRuntimeStateState = exportRuntimeStateState,
            statusState = statusState,
            messageScope = scope,
        )
    }

    // Predictive-back progress of the open full-screen tool (crop / mask geometry / sampler).
    var overlayBackProgress by screen.overlayBackProgressState

    // --- in-session undo / redo edit history ---
    // A snapshot = the full preset/recipe JSON (Presets.toJsonString — covers every
    // ParamsState field incl. raw WB/temp/tint) PLUS the editor-local manual rotation.
    val editHistory = session.history
    // The last SETTLED snapshot we committed. Capture coalescing pushes THIS (the
    // pre-edit state) onto undo when a newer settled state differs (see the capture
    // effect below), so one slider drag (which settles once) == one undo step.
    val committedSnapshotState = session.committedSnapshot
    var committedSnapshot by committedSnapshotState
    // Set true while we are programmatically restoring a snapshot (undo/redo). The
    // capture effect skips one settle cycle so the restore is NOT recorded as a new
    // edit — without this the restored state would push itself and we'd never escape.
    val restoringState = session.restoring
    var restoring by restoringState
    // Explicit conflict marker used only while AppRoot is recovering transient session IO.
    // Programmatic restore/default application keeps it false; a settled user edit sets it.
    var liveCursorMutated by screen.liveCursorMutatedState

    // Snapshot, restore, undo/redo and the settle step live on EditorSession (#452).
    fun snapshotNow(): EditSnapshot = session.snapshot()

    fun doUndo() {
        if (discardCropDraft()) {
            status = ctx.getString(R.string.editor_status_undo)
            return
        }
        session.undo() ?: return
        liveCursorMutated = true
        previewTick++
        status = ctx.getString(R.string.editor_status_undo)
    }

    fun doRedo() {
        // Redo would restore a recipe over a crop draft that never entered history, and the
        // draft's new adjustments would be lost for good: Redo waits until the draft is committed.
        if (cropDraftRef.value?.touched == true) return
        session.redo() ?: return
        liveCursorMutated = true
        previewTick++
        status = ctx.getString(R.string.editor_status_redo)
    }

    // Hold Undo: the whole history as a named list (timeline, index of the live state).
    var historySheet by screen.historySheetState

    /** Walks undo or redo from the live state at [from] to timeline index [to]. */
    fun jumpHistory(from: Int, to: Int) {
        discardCropDraft()
        var moved = false
        repeat(from - to) { if (session.undo() != null) moved = true }
        repeat(to - from) { if (session.redo() != null) moved = true }
        if (!moved) return
        liveCursorMutated = true
        previewTick++
        status = ctx.getString(if (to < from) R.string.editor_status_undo else R.string.editor_status_redo)
    }

    fun captureEditorSessionDocument(): EditorSessionDocument? = buildEditorSessionDocument(
        recipeReady = recipeReady,
        toolVm = toolVm,
        state = state,
        sourceState = sourceState,
        session = session,
        committedSnapshot = committedSnapshot,
        editHistory = editHistory,
        activeCategory = activeCategory,
        presetVm = presetVm,
        exportVm = exportVm,
    )

    fun checkpointEditorSession(): Boolean = runCatching {
        captureEditorSessionDocument()?.let { document ->
            editorProbe.publishLiveEditorSnapshot(editorInstance, document)
            onSessionCheckpoint(document, liveCursorMutated)
        } ?: false
    }.getOrElse {
        // Never echo session/source values into diagnostics; the exception class is enough
        // to distinguish schema/limit/programming failures for this app-generated file.
        Diag.w("editor session checkpoint rejected (${it.javaClass.simpleName})")
        false
    }
    sessionCheckpointAction[0] = ::checkpointEditorSession

    // Live-editor readiness and the lifecycle checkpoints (EditorSessionCheckpoint.kt).
    val currentSessionCheckpoint by rememberUpdatedState<() -> Boolean>(
        newValue = { checkpointEditorSession() },
    )
    EditorSessionLifecycleEffects(
        recipeReady = recipeReady,
        lifecycle = lifecycle,
        editorInstance = editorInstance,
        captureEditorSessionDocument = ::captureEditorSessionDocument,
        liveCursorMutated = { liveCursorMutated },
        onSessionCheckpoint = onSessionCheckpoint,
        currentSessionCheckpoint = { currentSessionCheckpoint() },
    )

    fun resetEditorCursorForSourceChange() {
        editHistory.clear()
        committedSnapshot = null
        restoring = true
        presetVm.onAction(PresetAction.ClearAmount)
        toolVm.onAction(EditorToolAction.ResetForSourceChange)
        if (recipeReady) {
            Recipes.resetToDefaults(state, settings, profiles)
        }
    }

    // One-time engine init. EditorViewModel loads the catalog (process-wide engine from
    // EngineHolder, profile ids, built-in looks) once, off the main thread. This effect
    // applies it to the composition: engine, profiles and the checkpoint restore land in the
    // same main-thread step, so no render can start with un-restored params.
    val editorCatalog by editorVm.catalog.collectAsStateWithLifecycle()
    LaunchedEffect(editorCatalog) {
        val loaded = editorCatalog ?: return@LaunchedEffect
        applyEditorCatalog(
            loaded = loaded,
            ctx = ctx,
            session = session,
            settings = settings,
            screen = screen,
            toolVm = toolVm,
            presetVm = presetVm,
            initialRestoredSession = initialRestoredSession,
            initialSessionRestoreNotice = initialSessionRestoreNotice,
            sourceUri = sourceUri,
            sourceKind = sourceKind,
            refreshPresets = ::refreshPresets,
            statusState = statusState,
        )
    }

    // --- Non-destructive recipe: restore-on-open ---
    val restoredRecipeKey = initialRestoredSession?.source?.uri
        ?.let(Uri::parse)
        ?.let(Recipes::keyFor)
    val lastRestoredKeyState = remember { mutableStateOf(restoredRecipeKey) }
    var lastRestoredKey by lastRestoredKeyState
    // A restored source identity proves nothing about its sidecar contents. Every composition
    // starts PENDING and only the exact generation classified by readResult may become writable.
    val recipeAccessState = remember(recipeKey) {
        mutableStateOf<EditorRecipeAccess>(
            recipeKey?.let { EditorRecipeAccess.Pending(it, recipeRepo.generation(it)) }
                ?: EditorRecipeAccess.None,
        )
    }
    var recipeAccess by recipeAccessState
    // --- Photo info (PhotoInfo.kt) ---
    // Read on demand, off the main thread, for the photo on screen; a source change while it
    // loads drops the result instead of showing the previous photo's facts.
    var photoInfo by screen.photoInfoState
    fun openPhotoInfo() {
        val uri = sourceUri ?: return
        val name = sourceName
        scope.launch {
            val info = withContext(dispatchers.io) {
                runCatching { readPhotoInfo(ctx, uri, name, null, null) }.getOrNull()
            }
            if (info != null && sourceUri == uri) photoInfo = info
        }
    }
    LaunchedEffect(sourceUri) { photoInfo = null }
    // Narrow editor panels share the phone toolbar anchors, set by the editor body.
    var spNarrow by remember { mutableStateOf(false) }
    var spMenuAnchor by remember { mutableStateOf<com.spectrafilm.app.silverphase.SpOrigin?>(null) }
    var spExportAnchor by remember { mutableStateOf<com.spectrafilm.app.silverphase.SpOrigin?>(null) }
    if (!spNarrow) {
        photoInfo?.let { info -> PhotoInfoSheet(info, onDismiss = { photoInfo = null }) }
        historySheet?.let { (timeline, current) ->
            EditHistorySheet(
                timeline = timeline,
                current = current,
                onPick = { to -> historySheet = null; jumpHistory(current, to) },
                onDismiss = { historySheet = null },
            )
        }
    }
    // A new photo has its own history; a list from the last one must not survive the switch.
    LaunchedEffect(sourceUri) { historySheet = null }

    // --- Apply previous edit (EditorPreviousEditActions.kt) ---
    val previousEditActions = rememberEditorPreviousEditActions(
        ctx, screen, session, presetVm, statusState, recipeKey, recipeReady, recipeRepo,
    )
    val previousEdit = previousEditActions.previousEdit
    fun applyPreviousEdit(edit: PreviousEdit) = previousEditActions.apply(edit)
    suspend fun offerPreviousEdit() = previousEditActions.offer()
    val recipeEditEpoch = remember { RecipeEditEpoch() }
    LaunchedEffect(recipeKey, recipeReady) {
        runRecipeRestore(
            ctx = ctx,
            screen = screen,
            session = session,
            settings = settings,
            recipeKey = recipeKey,
            recipeRepo = recipeRepo,
            restoredRecipeKey = restoredRecipeKey,
            lastRestoredKeyState = lastRestoredKeyState,
            profilesState = profilesState,
            recipeAccessState = recipeAccessState,
            statusState = statusState,
            offerPreviousEdit = { offerPreviousEdit() },
        )
    }

    // One-shot snackbar when a compressed DNG fell back to the system decoder.
    LaunchedEffect(dngFallbackNotice) {
        if (dngFallbackNotice) {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(
                message = ctx.getString(R.string.editor_snack_dng_fallback),
                withDismissAction = true,
            )
            dngFallbackNotice = false
        }
    }

    // Media-location, notification and legacy-storage asks around an export
    // (EditorExportPermissions.kt).
    val exportPermissions = rememberEditorExportPermissions(
        ctx, exportVm, settings, { sourceUri }, scope, snackbarHost,
    ) { status = it }
    val legacyStoragePermission = exportPermissions.legacyStoragePermission
    fun mediaLocationPermissionMissing() = exportPermissions.mediaLocationPermissionMissing()
    fun requestMediaLocationIfMissing() = exportPermissions.requestMediaLocationIfMissing()
    fun exportAskingNotificationsOnce(export: () -> Unit) = exportPermissions.exportAskingNotificationsOnce(export)

    // Source adoption: picking, opening and failure reconciliation (EditorSourceAdoption.kt).
    // Built per composition, like the local functions it replaces, so it captures this
    // composition's values; editor state it mutates is passed as state holders.
    val sourceAdoption = EditorSourceAdoption(
        ctx = ctx,
        scope = scope,
        dispatchers = dispatchers,
        session = session,
        sourceRuntime = sourceRuntime,
        sourceState = sourceState,
        sourceUriState = sourceUriState,
        sourceKindState = sourceKindState,
        rotationState = rotationState,
        statusState = statusState,
        previewTickState = previewTickState,
        activeCategoryState = activeCategoryState,
        snackbarHost = snackbarHost,
        retireSourceResources = ::retireSourceResources,
        resetEditorCursorForSourceChange = ::resetEditorCursorForSourceChange,
        checkpointEditorSession = ::checkpointEditorSession,
    )
    fun reconcileVisibleSourceAfterFailure(durable: SourceRestoreResult?) =
        sourceAdoption.reconcileVisibleSourceAfterFailure(durable)
    fun adoptSource(
        uri: Uri,
        kind: SourceKind,
        displayName: String,
        readyStatus: String,
        probeLabel: String? = null,
        ownedCopy: Uri? = null,
    ) {
        // A crop draft belongs to the photo on screen: commit it before a new photo (picker,
        // share or launcher shortcut) resets the cursor, so going back keeps the crop.
        closeCrop()
        sourceAdoption.adoptSource(uri, kind, displayName, readyStatus, probeLabel, ownedCopy)
    }

    LaunchedEffect(Unit) {
        if (!editorProbe.isArmed()) return@LaunchedEffect
        editorProbe.sourceProbeRequests.collect { request ->
            adoptSource(
                uri = Uri.parse(editorProbe.sourceProbeUri(request.label)),
                kind = SourceKind.PHOTO,
                displayName = "ticket139-source-${request.label.lowercase()}.png",
                readyStatus = "source probe ${request.label} selected",
                probeLabel = request.label,
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!editorProbe.isArmed()) return@LaunchedEffect
        editorProbe.previewCompletionRequests.collect {
            if (
                editorProbe.sourceProbeLabel(sourceUri?.toString()) == "A" &&
                sourceRenderAllowed
            ) {
                // A published frame normally makes this a grade-cache hit. Clearing only the
                // optional retained result forces the next settle through the real native path.
                gradeCache.clear()
                previewTick++
            }
        }
    }

    // --- source pickers ---
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val displayName = uri.lastPathSegment?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.editor_source_picked_photo_name)
            adoptSource(uri, SourceKind.PHOTO, displayName, ctx.getString(R.string.editor_status_photo_selected))
        }
    }
    // Shared dispatch for the document picker AND #162 inbound VIEW/SEND: one routing
    // decision (detectSourceKind, SourceDetect.kt), one adoption path. The picker accepts
    // */*, so every kind below is genuinely reachable — including the motion kinds, which
    // used to fall through the still-image test and be handed to LibRaw as corrupt RAW.
    fun adoptIncomingImage(uri: Uri, nameHint: String? = null, mimeHint: String? = null, ownedCopy: Uri? = null) =
        sourceAdoption.adoptIncomingImage(uri, nameHint, mimeHint, ownedCopy)
    val rawPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) adoptIncomingImage(uri)
    }
    // Batch export: this edit and the export sheet's settings on more photos
    // (EditorBatchExport.kt, BatchExport.kt).
    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            startBatchExport(dispatchers, exportVm, ctx, scope, state, engine, uris, snackbarHost) { status = it }
        }
    }
    // Half-frame pair: this photo and one more on one picture (HalfFramePair.kt).
    val pairPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            startHalfFramePair(
                dispatchers, exportVm,
                ctx, scope, state, engine, sourceUri, sourceName, sourceKind, rotation, uri, snackbarHost,
            ) { status = it }
        }
    }
    BatchExportProgressEffect(ctx, scope, snackbarHost) { status = it }
    // Shared / opened-with images and the launcher's "Open photo" shortcut
    // (EditorIncomingImages.kt).
    EditorIncomingImageEffects(
        incomingImageIntent = incomingImageIntent,
        launcherShortcut = launcherShortcut,
        ctx = ctx,
        dispatchers = dispatchers,
        sourceMutationGate = sourceMutationGate,
        photoPicker = photoPicker,
        adoptIncomingImage = { uri, nameHint, mimeHint, ownedCopy ->
            adoptIncomingImage(uri, nameHint, mimeHint, ownedCopy)
        },
        setStatus = { status = it },
    )
    val pendingPresetExportState = screen.pendingPresetExportState
    var pendingPresetExport by pendingPresetExportState
    // Preset import/export and LUT save launchers (EditorPresetFiles.kt).
    val presetFiles = rememberEditorPresetFiles(
        ctx, scope, dispatchers, state, presetRepo, pendingPresetExportState, pendingLutTextState,
        onImported = { previewTick++ },
        setStatus = { status = it },
    )
    val presetImporter = presetFiles.presetImporter
    val presetExporter = presetFiles.presetExporter
    val savedPresetExporter = presetFiles.savedPresetExporter
    val lutExporter = presetFiles.lutExporter

    // A saved result stays "current" (the pill's Share sends that file again) until the
    // user's next edit -- a slider drag or any settled change -- or a move to another tool
    // panel, so Share never sends a file the photo has since moved on from.
    //
    // The baseline is what the run RENDERED, taken when it starts (the render mask blocks every
    // edit until it ends), never what the editor holds when the result lands: the result is
    // published only after the terminal checkpoint flushes, and an edit made during that
    // window must still count against it (Codex on #535). Falls back to the state at
    // publication after recreation, which loses the remembered baseline.
    // The live edit state (params + rotation) is compared, not the undo history's committed
    // snapshot, which settles on its own 500 ms debounce and would read as an "edit" when it
    // catches up after a fast Share (Codex on #535).
    var exportBaseline by screen.exportBaselineState
    LaunchedEffect(exportInFlight) {
        if (exportInFlight) exportBaseline = snapshotNow() to activeCategory
    }
    if (exportDone && exportLastShare != null) {
        LaunchedEffect(exportLastShare) {
            val base = exportBaseline
            val renderedState = base?.first ?: snapshotNow()
            val categoryAtSave = if (base != null) base.second else activeCategory
            snapshotFlow {
                interacting.value ||
                    snapshotNow() != renderedState ||
                    activeCategory != categoryAtSave
            }.first { it }
            exportVm.onAction(ExportAction.DismissResult)
            checkpointEditorSession()
        }
    }

    // The source's access expired: offer to choose it again (EditorCatalogApply.kt).
    EditorSourceExpiredEffect(
        sourceAuthorizationRequired = sourceAuthorizationRequired,
        sourceKind = sourceKind,
        ctx = ctx,
        exportVm = exportVm,
        snackbarHost = snackbarHost,
        rawPicker = rawPicker,
        photoPicker = photoPicker,
    )

    // Source decode requests and the cached preview / zoom loaders (EditorSourceLoader.kt).
    // Built per composition, like the local functions it replaces.
    val sourceLoader = EditorSourceLoader(
        ctx = ctx,
        scope = scope,
        dispatchers = dispatchers,
        state = state,
        sourceRenderAllowed = sourceRenderAllowed,
        sourceUriState = sourceUriState,
        sourceKindState = sourceKindState,
        rotationState = rotationState,
        sourceCache = sourceCache,
        zoomSourceCache = zoomSourceCache,
        previewDecodeFlight = previewDecodeFlight,
        zoomDecodeFlight = zoomDecodeFlight,
        dngFallbackNoticeState = dngFallbackNoticeState,
    )
    fun currentSourceDecodeRequest(): SourceDecodeRequest = sourceLoader.currentSourceDecodeRequest()
    suspend fun cachedSourceDecodeRequest(cache: DecodedSourceCache, maxEdge: Int): CachedSourceDecodeRequest =
        sourceLoader.cachedSourceDecodeRequest(cache, maxEdge)

    // Stock and Look strip preview sessions (EditorStockPreviewHosts.kt).
    fun stockPreviewHost(vm: StockPreviewViewModel, forFilm: Boolean) = editorStockPreviewHost(
        vm, forFilm, ctx, state, sourceCache, dispatchers,
        sourceUri = sourceUri?.toString()?.takeIf { sourceRenderAllowed },
        canOpen = { sourceRenderAllowed && sourceUri != null },
        cachedSourceDecodeRequest = { cache, edge -> cachedSourceDecodeRequest(cache, edge) },
    )

    fun presetPreviewHost(vm: StockPreviewViewModel) = editorPresetPreviewHost(
        vm, ctx, state, sourceCache, dispatchers,
        sourceUri = sourceUri?.toString()?.takeIf { sourceRenderAllowed },
        canOpen = { sourceRenderAllowed && sourceUri != null },
        cachedSourceDecodeRequest = { cache, edge -> cachedSourceDecodeRequest(cache, edge) },
        builtInGroups = { builtInGroups },
        lastBuiltInOutput = { lastBuiltInOutput },
        presetBaselineJson = { presetBaselineJson },
    )

    suspend fun loadSourceCachedForPreview(maxEdge: Int): DecodedSourceCache.Lease =
        sourceLoader.loadSourceCachedForPreview(maxEdge)
    suspend fun loadSourceCachedForZoom(maxEdge: Int): DecodedSourceCache.Lease =
        sourceLoader.loadSourceCachedForZoom(maxEdge)

    zoom.loadSourceCachedForZoom = ::loadSourceCachedForZoom
    fun scheduleZoomPrewarm() = zoom.scheduleZoomPrewarm()
    fun openMagnifier(nx: Float, ny: Float) = zoom.openMagnifier(nx, ny)
    fun renderRoi(roi: RoiRect) {
        if (state.filmEdge.spatial) zoom.clearRoi() else zoom.renderRoi(roi)
    }
    fun clearRoi() = zoom.clearRoi()

    // Every preview render effect: draft, skin, settle, GPU feed, the render trigger and the
    // idle full-resolution pre-render (EditorPreviewEffects.kt). Same order as before.
    val snapshot = remember(state) { derivedStateOf { state.toParams() } }
    EditorPreviewEffects(
        ctx = ctx,
        dispatchers = dispatchers,
        screen = screen,
        session = session,
        settings = settings,
        toolVm = toolVm,
        viewerVm = viewerVm,
        exportVm = exportVm,
        engineState = engineState,
        sourceUriState = sourceUriState,
        sourceKindState = sourceKindState,
        sourceRenderAllowed = sourceRenderAllowed,
        previewRevision = previewRevision,
        interacting = interacting,
        statusState = statusState,
        gpuEnabled = gpuEnabled,
        zoomPrewarmEligible = zoomPrewarmEligible,
        snapshot = snapshot,
        exportOptions = exportOptions,
        exportInFlight = exportInFlight,
        exportRuntimeStateState = exportRuntimeStateState,
        retireSourceResources = ::retireSourceResources,
        scheduleZoomPrewarm = ::scheduleZoomPrewarm,
        requireSourceAuthorization = session::requireSourceAuthorization,
        currentSourceDecodeRequest = ::currentSourceDecodeRequest,
        cachedSourceDecodeRequest = ::cachedSourceDecodeRequest,
        loadSourceCachedForPreview = ::loadSourceCachedForPreview,
    )

    // Recipe auto-save, undo/redo capture and the session-cursor checkpoints (EditorSettleEffects.kt).
    EditorSettleEffects(
        ctx = ctx,
        session = session,
        screen = screen,
        exportVm = exportVm,
        snapshot = snapshot,
        recipeKey = recipeKey,
        recipeReady = recipeReady,
        defaultsJson = defaultsJson,
        rotation = rotation,
        recipeAccess = recipeAccess,
        sourceUri = sourceUri,
        sourceKind = sourceKind,
        sourceName = sourceName,
        sourceAuthorizationRequired = sourceAuthorizationRequired,
        activeCategory = activeCategory,
        toolUi = toolUi,
        presetUi = presetUi,
        recipeEditEpoch = recipeEditEpoch,
        recipeRepo = recipeRepo,
        recipeAccessState = recipeAccessState,
        statusState = statusState,
        onLiveCursorMutated = { liveCursorMutated = true },
        checkpointEditorSession = ::checkpointEditorSession,
        currentSessionCheckpoint = { currentSessionCheckpoint() },
    )

    // catalog-grouped profile options for the Simulation pickers + Settings.
    val available = profiles.ifEmpty {
        listOf(state.filmProfile, state.printProfile).distinct()
    }
    val filmGroups = remember(available, catalogReady) {
        StockCatalog.optionsFor(ctx, available, forFilm = true).toGroups()
    }
    val printGroups = remember(available, catalogReady) {
        StockCatalog.optionsFor(ctx, available, forFilm = false).toGroups()
    }
    LaunchedEffect(filmGroups, printGroups) { onProfileGroups(filmGroups, printGroups) }

    // Export / Share entry points and their deferral while a render runs (EditorExportActions.kt).
    val exportActions = rememberEditorExportActions(
        dispatchers = dispatchers,
        exportVm = exportVm,
        exportPresetVm = exportPresetVm,
        ctx = ctx,
        scope = scope,
        state = state,
        engineState = engineState,
        previewBusyState = previewBusyState,
        sourceRenderAllowed = sourceRenderAllowed,
        sourceUriState = sourceUriState,
        sourceNameState = sourceNameState,
        exportSourceIdentity = exportSourceIdentity,
        snackbarHost = snackbarHost,
        legacyStoragePermission = legacyStoragePermission,
        mediaLocationPermissionMissing = ::mediaLocationPermissionMissing,
        currentSourceDecodeRequest = ::currentSourceDecodeRequest,
        checkpointEditorSession = ::checkpointEditorSession,
        onSettingsAction = onSettingsAction,
        statusState = statusState,
    )
    fun launchEditorExport(exportPresets: ExportPresetDocument) = exportActions.launchEditorExport(exportPresets)
    fun requestExport() = exportActions.requestExport()
    fun requestShare() = exportActions.requestShare()

    // Hardware keyboard shortcuts + Back handling (EditorBackAndShortcuts.kt).
    EditorBackAndShortcuts(
        toolUi = toolUi,
        toolVm = toolVm,
        viewerUi = viewerUi,
        viewerVm = viewerVm,
        exportMaskVisible = exportMaskVisible,
        magnifierOpen = magnifierOpen,
        activeCategory = activeCategory,
        onCloseCategory = { activeCategory = null },
        onCloseCrop = ::closeCrop,
        cropDraftPending = cropDraftRef.value?.touched == true,
        onBackPressedDispatcher = onBackPressedDispatcher,
        setEditorShortcutHandler = setEditorShortcutHandler,
        editHistory = editHistory,
        doUndo = ::doUndo,
        doRedo = ::doRedo,
        openPhotoPicker = {
            closeCrop()
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        requestExport = { closeCrop(); requestExport() },
        hasSource = sourceUri != null,
        openPhotoInfo = ::openPhotoInfo,
        previousEdit = previousEdit,
        applyPreviousEdit = ::applyPreviousEdit,
        onOverlayBackProgress = { overlayBackProgress = it },
        onPanelBackProgress = { panelBackProgress = it },
        closeMagnifier = ::closeMagnifier,
    )

    // The photo's EXIF capture date for the date-back imprint's default text,
    // read off the main thread once per source.
    LaunchedEffect(state, sourceUri) {
        state.filmEdgeCapture = FilmEdgeCapture()
        val uri = sourceUri ?: return@LaunchedEffect
        val capture = withContext(dispatchers.io) { FilmEdgeCapture.read(ctx, uri) }
        if (sourceUriState.value == uri) state.filmEdgeCapture = capture
    }
    val photoDateStamp = state.filmEdgeCapture.legacyDate
    // Worded only while the Frame tab is open; the export words its own from the same facts.
    val frameCaption = if (activeCategory == Category.FRAME) {
        rememberFrameCaption(exportOptions.frame, sourceUri, state.filmProfile)
    } else null
    // RAW cleanup acts inside the RAW decode, so it is only offered for a RAW.
    val isRawSource = sourceKind == SourceKind.RAW
    fun toolShown(c: Category) = c != Category.RAW_CLEANUP || isRawSource
    // One tool's controls, shared by every branch so the per-tool `when` below
    // is not duplicated.
    val toolContent: @Composable ColumnScope.(Category) -> Unit = { tool ->
        EditorToolContent(
            tool = tool,
            ctx = ctx,
            scope = scope,
            dispatchers = dispatchers,
            screen = screen,
            settings = settings,
            session = session,
            toolVm = toolVm,
            toolUi = toolUi,
            viewerVm = viewerVm,
            viewerUi = viewerUi,
            presetVm = presetVm,
            versionsVm = versionsVm,
            filmPreviewVm = filmPreviewVm,
            printPreviewVm = printPreviewVm,
            isRawSource = isRawSource,
            preview = preview,
            previewBusy = previewBusy,
            interacting = interacting,
            photoDateStamp = photoDateStamp,
            filmGroups = filmGroups,
            printGroups = printGroups,
            onOpenFilmCurves = onOpenFilmCurves,
            onOpenPrintCurves = onOpenPrintCurves,
            stockPreviewHost = ::stockPreviewHost,
            presetPreviewHost = { presetPreviewHost(presetPreviewVm) },
            checkpointEditorSession = ::checkpointEditorSession,
            sourceUri = sourceUri,
            presetRepo = presetRepo,
            recipeKey = recipeKey,
            builtInGroups = builtInGroups,
            presetUi = presetUi,
            presetImporter = presetImporter,
            presetExporter = presetExporter,
            savedPresetExporter = savedPresetExporter,
            lutExporter = lutExporter,
            statusState = statusState,
            engineState = engineState,
            applyWithAmount = ::applyWithAmount,
            previousEdit = previousEdit,
            applyPreviousEdit = ::applyPreviousEdit,
            sourceRuntime = sourceRuntime,
            recipeRepo = recipeRepo,
            recipeEditEpoch = recipeEditEpoch,
            photoPicker = photoPicker,
            rawPicker = rawPicker,
            recipeAccessState = recipeAccessState,
            profilesState = profilesState,
            retireSourceResources = ::retireSourceResources,
            resetEditorCursorForSourceChange = ::resetEditorCursorForSourceChange,
            reconcileVisibleSourceAfterFailure = ::reconcileVisibleSourceAfterFailure,
            exportOptions = exportOptions,
            onExportOptionsChange = { exportVm.onAction(ExportAction.SetOptions(it)) },
            frameCaption = frameCaption,
        )
    }
    // The Crop tab draws its frame on the photo only once the preview on screen is the
    // crop-free render; until then it shows the photo as it was and a loading panel.
    val cropFreePreview by screen.cropFreePreviewState
    val cropCanvasBmp = preview?.takeIf { toolUi.cropOpen && it === cropFreePreview && !it.isRecycled }
    val cropDraft = cropCanvasBmp?.let {
        rememberCropDraft(it, state.crop, state.cropCenter, state.cropSize, state.straightenDegrees, cropDraftEpoch)
    }
    SideEffect { cropDraftRef.value = if (toolUi.cropOpen) cropDraft else null }
    // The Frame tab shows the photo with its frame, painted by the export's own renderer.
    val frameCanvasBmp = preview?.takeIf {
        activeCategory == Category.FRAME && !it.isRecycled && !exportOptions.frameSpec.isNone
    }
    val ownCanvas = cropDraft != null || frameCanvasBmp != null
    val previewRegion: @Composable (controlsBottomInset: Dp, editOpen: Boolean) -> Unit = { panelInset, editOpen ->
        val cropBitmap = cropCanvasBmp
        val draft = cropDraft
        val framePreview = frameCanvasBmp
        if (cropBitmap != null && draft != null) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                CropCanvas(bitmap = cropBitmap, draft = draft, bottomInset = panelInset)
            }
        } else if (framePreview != null) {
            FramedPreviewCanvas(
                preview = framePreview,
                spec = exportOptions.frameSpec,
                bottomInset = panelInset,
                caption = frameCaption,
            )
        } else PreviewRegion(
            zoomRequests = viewerVm.zoomRequests,
            controlsBottomInset = panelInset,
            peekOriginal = viewerUi.originalPeek,
            preview = preview,
            placeholder = openPlaceholder,
            before = beforePreview,
            busy = previewBusy,
            decoding = decoding,
            exporting = exportInFlight,
            lastRenderMs = lastRenderMs,
            renderErr = renderErr,
            compareMode = viewerUi.compare,
            showHistogram = viewerUi.histogram,
            showClipping = viewerUi.showClipping,
            gpuEnabled = gpuEnabled,
            gpuProxy = gpuProxy,
            gpuLut = gpuLut,
            gpuGain = gpuGain,
            // Single tap on the photo = show/hide the chrome. The point is
            // ignored: this is a toggle, not a pick. The magnifier that used to
            // own this gesture now has the button below, which is the affordance
            // the in-app guide already claimed existed.
            onPointPicked = { _, _ -> viewerVm.onAction(EditorViewerAction.ToggleChrome) },
            onOpenMagnifier = { openMagnifier(0.5f, 0.5f) },
            renderKey = previewTick,
            roiOverlay = roiOverlay,
            onRoiSettled = { renderRoi(it) },
            onRoiCleared = { clearRoi() },
            swipe = photoSwipe,
            // Only right after a control was used (scrubArmed) and while it is still on
            // screen: otherwise the sideways drag is the next/previous photo.
            scrub = photoScrub.takeIf {
                editOpen && scrubArmed.value && lastControlAlive.value?.value == true
            },
            strip = folderStrip,
        )
    }

    // ============================ LAYOUT ============================
    // Overlay predicates, shared with the overlay branches below the chrome.
    val cropBmp = preview
    val maskGeometryShown = toolUi.maskGeometryOpen && cropBmp != null &&
        toolUi.maskIndex in state.localAdjustments.indices
    val sampleMask = state.localAdjustments.getOrNull(toolUi.maskIndex)?.mask
    val sampleReady = sourceRenderAllowed && toolUi.sampleOpen && cropBmp != null && (
        toolUi.sampleWb ||
            (sampleMask != null &&
                (if (toolUi.sampleLuminance) sampleMask.luminanceRange != null else sampleMask.colorRange != null))
        )
    // #181: a full-screen tool overlay is modal. Compose overlays share the window, so the
    // chrome beneath would otherwise stay reachable by TalkBack explore-by-touch.
    val modalOverlayShown = magnifierOpen || maskGeometryShown || sampleReady

    // --- drag and drop a photo in (ImageDrop.kt) ---
    // Same adoption path as a shared/opened photo. Refused while a modal tool overlay or
    // an export is up, exactly when the Open button is out of reach too.
    val acceptDrop by rememberUpdatedState(
        !modalOverlayShown && !toolUi.cropOpen && !exportMaskVisible && !exportInFlight,
    )
    val onImageDrop by rememberUpdatedState<(DragAndDropEvent) -> Boolean> { event ->
        val dragEvent = event.toAndroidDragEvent()
        val uri = droppedContentUri(dragEvent.clipData)
        if (uri == null || !acceptDrop) {
            false
        } else {
            // Null when the drag came from this app; a cross-app drop needs the grant.
            requestDragAndDropPermissions(dragEvent)?.let { heldDropPermissions += it }
            adoptIncomingImage(uri)
            true
        }
    }
    // ===================== SILVERPHASE PHONE EDITOR (port) =====================
    // Narrow windows (ordinary phones in portrait) use the Silverphase v1.2.5 iPhone layout;
    // wide windows keep the existing pane layout (EditorBody/EditorTopBar) unchanged.
    val spUi = remember { com.spectrafilm.app.silverphase.SpUiKeys() }
    val spMemory = remember(ctx) { com.spectrafilm.app.silverphase.SpMaterialMemory(ctx) }
    var spStageId by rememberSaveable { mutableStateOf<String?>(null) }
    val spStage = com.spectrafilm.app.silverphase.SpStage.entries.firstOrNull { it.id == spStageId }
    var spMaterials by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val spMaterialOrigins = remember { arrayOfNulls<com.spectrafilm.app.silverphase.SpOrigin>(2) }
    var spToolsOpen by rememberSaveable { mutableStateOf(false) }
    var spToolName by rememberSaveable { mutableStateOf<String?>(null) }
    val spTool = spToolName?.let { n -> Category.entries.firstOrNull { it.name == n } }
    var spPixelSize by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceUri) {
        val uri = sourceUri
        spPixelSize = if (uri == null) null else withContext(dispatchers.io) {
            com.spectrafilm.app.silverphase.spReadPixelSize(ctx, uri)
        }
    }
    val spReduceMotion = com.spectrafilm.app.silverphase.LocalSpReduceMotion.current
    val spPrintIds = remember(printGroups) { printGroups.flatMap { g -> g.options.map { it.id } } }
    fun spResetCategories(vararg cats: Category) {
        for (c in cats) categoryDefaults(c)?.let { d -> runCatching { Presets.decode(d, state) } }
        previewTick++
    }
    // The host is remembered once (a new object every EditorScreen recomposition made the whole
    // stage panel recompose on every preview render). Values that are plain vals here are read
    // through rememberUpdatedState, so the remembered object always sees the current ones and
    // panel rows that read them are still invalidated when they change.
    val spRenderAllowed by rememberUpdatedState(sourceRenderAllowed)
    val spExportInFlight by rememberUpdatedState(exportInFlight)
    val spPrintIdsNow by rememberUpdatedState(spPrintIds)
    val spSliderInteraction by rememberUpdatedState(sliderInteraction)
    val spCropUi by rememberUpdatedState<@Composable ColumnScope.() -> Unit> {
        CropPanelContent(
            draft = cropDraft,
            onRotate = { commitCropDraft(); rotation = rotation.next() },
        )
    }
    // 拍摄: the scene-latitude measurement (SpLatitude.kt), recomputed only while the 拍摄 panel
    // is open, 300 ms after the last parameter change (never per ruler frame), off the main thread,
    // from the same cached source proxy the preview renders. The medium probe is cached by its
    // parameters, so only a film/paper/enlarger/scanner/Exp. Comp. change re-renders the ramp.
    var spLatitudeUi by remember { mutableStateOf<com.spectrafilm.app.silverphase.SpLatitudeUi>(com.spectrafilm.app.silverphase.SpLatitudeUi.Idle) }
    var spLatitudeRetry by remember { mutableIntStateOf(0) }
    val spCameraOpen = spStage == com.spectrafilm.app.silverphase.SpStage.CAMERA
    LaunchedEffect(engine, sourceUri, spCameraOpen, spLatitudeRetry) {
        val e = engine
        if (e == null || sourceUri == null || !spCameraOpen) return@LaunchedEffect
        // While a ruler is dragged the flow reads only `interacting`, so no SpektraParams is built
        // per drag frame on the main thread; the release publishes the settled params once.
        snapshotFlow { if (interacting.value) null else session.params.toParams() }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { p ->
                delay(300)
                // C02: never meter while a ruler is under the finger. The ramp simulate shares the
                // engine with the live DRAFT preview, so a pause mid-drag must not start it.
                snapshotFlow { interacting.value }.first { !it }
                val prev = spLatitudeUi
                spLatitudeUi = if (prev is com.spectrafilm.app.silverphase.SpLatitudeUi.Ready) prev.copy(refreshing = true)
                else com.spectrafilm.app.silverphase.SpLatitudeUi.Measuring
                val started = System.nanoTime()
                val result = try {
                    Result.success(withContext(dispatchers.default) {
                        loadSourceCachedForPreview(session.params.previewMaxSize.coerceAtLeast(256)).use { lease ->
                            com.spectrafilm.app.silverphase.SpLatitudeMeter.measure(e, lease.image, p)
                        }
                    })
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                spLatitudeUi = result.fold(
                    onSuccess = { lat ->
                        Diag.i("sp latitude %.0f ms: shadow %+.2f highlight %+.2f within %.3f n=%d ev=%+.2f".format(
                            (System.nanoTime() - started) / 1e6, lat.shadow, lat.highlight, lat.within, lat.samples, lat.meterEv))
                        com.spectrafilm.app.silverphase.SpLatitudeUi.Ready(lat, refreshing = false)
                    },
                    onFailure = { t ->
                        Diag.w("sp latitude failed: ${t.message}")
                        com.spectrafilm.app.silverphase.SpLatitudeUi.Failed(t.message ?: t.javaClass.simpleName)
                    },
                )
            }
    }
    LaunchedEffect(sourceUri) { spLatitudeUi = com.spectrafilm.app.silverphase.SpLatitudeUi.Idle }
    val spHost = remember(session) { object : com.spectrafilm.app.silverphase.SpEditorHost, com.spectrafilm.app.silverphase.SpMaterialOpener {
        override val latitude: com.spectrafilm.app.silverphase.SpLatitudeUi get() = spLatitudeUi
        override fun retryLatitude() { spLatitudeRetry++ }
        override val state: ParamsState get() = session.params
        override val isRaw: Boolean get() = sourceKind == SourceKind.RAW
        override val imageAspect: Float get() = preview?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1.5f
        override val enabled: Boolean get() = engine != null && spRenderAllowed && !spExportInFlight
        override val filmId: String get() = session.params.filmProfile
        override val printId: String get() = session.params.printProfile
        override val scanFilm: Boolean get() = session.params.scanFilm
        // Silverphase Digital Intermediate: scan_film wins (ProcessContract.wire), and a
        // positive film has no negative to reverse, so it is not offered there.
        override val digitalIntermediate: Boolean
            get() = session.params.digitalIntermediate && !session.params.scanFilm
        override val digitalIntermediateAvailable: Boolean
            get() = com.spectrafilm.app.silverphase.SpCatalog.stock(ctx, session.params.filmProfile)?.isPositive != true
        override fun setDigitalIntermediate() {
            if (!digitalIntermediateAvailable) return
            val s = session.params
            s.scanFilm = false
            s.digitalIntermediate = true
        }
        override fun clearDigitalIntermediate() { session.params.digitalIntermediate = false }
        override fun setDigitalIntermediateBlueCompensation(on: Boolean) {
            session.params.digitalIntermediateBlueCompensation = on
        }
        override fun setAntihalationMode(mode: String) {
            session.params.antihalationMode = if (mode in com.spectrafilm.engine.ANTIHALATION_MODES) mode else "profile"
        }
        override fun setExtendedDynamicRange(on: Boolean) { session.params.extendedDynamicRange = on }
        override fun setMeteringMethod(method: String?) {
            val s = session.params
            if (method == null || method !in com.spectrafilm.engine.AUTO_EXPOSURE_METHODS) {
                s.autoExposure = false
            } else {
                s.autoExposure = true
                s.autoExposureMethod = method
            }
        }
        override fun interaction(active: Boolean) {
            if (active) spSliderInteraction.onChange(null) else spSliderInteraction.onFinished()
        }
        override fun openMaterials(paper: Boolean) { spMaterials = paper }
        override fun materialOrigin(paper: Boolean, origin: com.spectrafilm.app.silverphase.SpOrigin) {
            spMaterialOrigins[if (paper) 1 else 0] = origin
        }
        override fun setFilm(stock: com.spectrafilm.app.silverphase.SpStock) {
            val s = session.params
            val oldFilm = s.filmProfile
            if (stock.id == oldFilm) return
            val oldPositive = com.spectrafilm.app.silverphase.SpCatalog.stock(ctx, oldFilm)?.isPositive == true
            s.filmProfile = stock.id
            val oldPrint = s.printProfile
            val newPrint = SilverPrint.printAfterFilmChange(
                oldFilm = oldFilm, newFilm = stock.id, print = oldPrint,
                lastColourPrint = s.lastColourPrint, available = spPrintIdsNow,
                targetPrint = stock.targetPrint,
            )
            s.lastColourPrint = SilverPrint.colourPrintToRemember(
                newFilm = stock.id, oldPrint = oldPrint, newPrint = newPrint, remembered = s.lastColourPrint,
            )
            if (newPrint != oldPrint) s.printProfile = newPrint
            // Silverphase: a positive film is scanned directly; leaving one returns to the print.
            if (stock.isPositive) s.scanFilm = true else if (oldPositive) s.scanFilm = false
            // A positive film cannot carry the Digital Intermediate.
            if (stock.isPositive) s.digitalIntermediate = false
        }
        override fun setPaper(stock: com.spectrafilm.app.silverphase.SpStock?) {
            val s = session.params
            // Choosing a paper (or none) leaves the Digital Intermediate, which replaces the paper.
            s.digitalIntermediate = false
            if (stock == null) { s.scanFilm = true; return }
            s.scanFilm = false
            if (stock.id != s.printProfile) { s.printProfile = stock.id; s.lastColourPrint = null }
        }
        @Composable override fun ColumnScope.cropControls() { spCropUi(this) }
    } }
    fun spResetStage(st: com.spectrafilm.app.silverphase.SpStage) {
        when (st) {
            com.spectrafilm.app.silverphase.SpStage.CAMERA -> spResetCategories(Category.EXPOSURE, Category.RAW_WB, Category.CAMERA_DIFFUSION)
            com.spectrafilm.app.silverphase.SpStage.FILM -> spResetCategories(Category.FILM_FORMAT, Category.GRAIN, Category.HALATION)
            com.spectrafilm.app.silverphase.SpStage.DEVELOPMENT -> {
                state.filmGammaFactor = PARAM_DEFAULTS.filmGammaFactor
                spResetCategories(Category.COUPLERS)
            }
            com.spectrafilm.app.silverphase.SpStage.PRINT -> {
                state.morphActive = PARAM_DEFAULTS.morphActive
                state.morphGammaFactor = PARAM_DEFAULTS.morphGammaFactor
                spResetCategories(Category.PRINT_EXPOSURE, Category.PRINT_COLOR, Category.PREFLASH, Category.ENLARGER)
            }
            com.spectrafilm.app.silverphase.SpStage.OUTPUT -> {
                state.extendedDynamicRange = PARAM_DEFAULTS.extendedDynamicRange
                spResetCategories(Category.SCANNER, Category.GLARE)
            }
            com.spectrafilm.app.silverphase.SpStage.GEOMETRY -> {
                discardCropDraft()
                state.crop = PARAM_DEFAULTS.crop
                state.cropCenter = PARAM_DEFAULTS.cropCenter
                state.cropSize = PARAM_DEFAULTS.cropSize
                state.straightenDegrees = PARAM_DEFAULTS.straightenDegrees
                previewTick++
            }
            com.spectrafilm.app.silverphase.SpStage.FILM_EDGE -> spResetCategories(Category.DATE_STAMP)
        }
    }
    // C05: the export state carries its percentage, so a long export reads as moving.
    val spExportFraction by ExportWorkRuntime.progress.collectAsStateWithLifecycle()
    val spStatusText = when {
        exportInFlight -> stringResource(R.string.sp_state_exporting) +
            (if (spExportFraction > 0f) " ${(spExportFraction * 100).toInt().coerceIn(0, 99)}%" else "")
        viewerUi.originalPeek -> stringResource(R.string.sp_state_original)
        decoding -> stringResource(R.string.sp_state_reading)
        previewBusy -> stringResource(R.string.sp_state_processing)
        renderErr != null -> stringResource(R.string.sp_state_failed)
        preview != null -> stringResource(R.string.sp_state_preview)
        else -> null
    }
    val spQuickHelp = LocalQuickHelp.current
    val spPhoneBody: @Composable () -> Unit = {
        // Entering / leaving 构图 opens / closes the Android crop tool on the canvas.
        // The export frame remains in the separate tools drawer.
        LaunchedEffect(spStage) {
            if (spStage == com.spectrafilm.app.silverphase.SpStage.GEOMETRY) {
                if (!toolVm.current.cropOpen) toolVm.onAction(EditorToolAction.OpenCrop)
            } else closeCrop()
        }
        LaunchedEffect(spStage, spTool, spToolsOpen) {
            val want = when {
                spToolsOpen && spTool != null -> spTool
                else -> null
            }
            if (activeCategory != want) {
                if (want == null) activeCategory = null else openCategory(want)
            }
        }
        com.spectrafilm.app.silverphase.SpPhoneEditorBody(
            stage = spStage,
            onStage = { st -> spMaterials = null; spToolsOpen = false; spToolName = null; spStageId = st.id },
            onClosePanel = { spMaterials = null; spStageId = null },
            onResetStage = ::spResetStage,
            tabsEnabled = !exportInFlight && sourceUri != null,
            chromeHidden = viewerUi.chromeHidden,
            backEnabled = !magnifierOpen && !modalOverlayShown && !exportMaskVisible,
            reduceMotion = spReduceMotion,
            reduceTransparency = false,
            materialsOpenForPaper = spMaterials,
            materialOrigin = spMaterials?.let { spMaterialOrigins[if (it) 1 else 0] },
            onMaterialsDone = { spMaterials = null },
            toolsOpen = spToolsOpen,
            toolsTitle = spTool?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sp_more_tools_title),
            toolsBack = if (spTool != null) ({ spToolName = null }) else null,
            onToolsClose = { spToolsOpen = false; spToolName = null },
            onBack = {
                closeCrop()
                checkpointEditorSession()
                flushRecipeBeforeLeaving(
                    state, recipeKey, recipeReady, recipeAccess, defaultsJson,
                    rotation, sourceName, hasRecipe, recipeRepo,
                )
                onOpenLibrary()
            },
            exportVisible = engine != null && sourceRenderAllowed,
            exportEnabled = !exportInFlight,
            onExport = { closeCrop(); requestExport() },
            onMenuOrigin = { spMenuAnchor = it },
            onExportOrigin = { spExportAnchor = it },
            menuItems = {
                val hasPhoto = preview != null || gpuProxy != null
                listOf(
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_import_from_photos)) {
                        closeCrop()
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_import_from_files)) {
                        closeCrop(); rawPicker.launch(arrayOf("*/*"))
                    },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_undo), editHistory.canUndo || cropDraft?.touched == true, divider = true) { doUndo() },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_redo), editHistory.canRedo && cropDraft?.touched != true) { doRedo() },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_edit_history), editHistory.canUndo || editHistory.canRedo) {
                        historySheet = historyTimeline(editHistory.snapshotState(), snapshotNow())
                    },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_reset_this_photo), sourceUri != null) {
                        spStageId = null; spToolName = Category.SOURCE.name; spToolsOpen = true
                    },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_zoom_to_fit), hasPhoto && !ownCanvas, divider = true) {
                        viewerVm.requestZoom(ViewerZoom.FIT)
                    },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_compare_original), hasPhoto && !ownCanvas, checked = viewerUi.compare && !ownCanvas) {
                        viewerVm.onAction(EditorViewerAction.ToggleCompare)
                    },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_histogram), hasPhoto && !ownCanvas, checked = viewerUi.histogram && !ownCanvas) {
                        viewerVm.onAction(EditorViewerAction.ToggleHistogram)
                    },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_clip_warnings), hasPhoto && !ownCanvas, checked = viewerUi.clipWarnings && !ownCanvas) {
                        viewerVm.onAction(EditorViewerAction.ToggleClipWarnings)
                    },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_loupe), hasPhoto) { openMagnifier(0.5f, 0.5f) },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_photo_info), sourceUri != null, divider = true) { openPhotoInfo() },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_share), exportDone && exportLastShare != null) { closeCrop(); requestShare() },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_more_tools)) {
                        spStageId = null; spMaterials = null; spToolName = null; spToolsOpen = true
                    },
                ) + listOfNotNull(
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_settings), divider = true) { closeCrop(); checkpointEditorSession(); onOpenSettings() },
                    spQuickHelp?.let { qh -> com.spectrafilm.app.silverphase.SpMenuItem(ctx.getString(R.string.help_menu)) { qh.open() } },
                    com.spectrafilm.app.silverphase.SpMenuItem(spS(R.string.sp_about)) { closeCrop(); checkpointEditorSession(); onOpenAbout() },
                )
            },
            slots = com.spectrafilm.app.silverphase.SpPhoneSlots(
                canvas = {
                    Box(
                        Modifier.fillMaxSize().spHoldToCompare((preview != null || gpuProxy != null) && !ownCanvas) { on ->
                            viewerVm.onAction(EditorViewerAction.SetOriginalPeek(on))
                        },
                    ) { previewRegion(0.dp, false) }
                },
                status = {
                    com.spectrafilm.app.silverphase.SpStatusOverlay(
                        fileName = sourceName,
                        pixelSize = spPixelSize,
                        state = spStatusText,
                        progress = if (exportInFlight) spExportFraction else null,
                    )
                },
                empty = if (sourceUri == null && preview == null) ({
                    com.spectrafilm.app.silverphase.SpEmptyEditor {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }) else null,
                materialList = { paper ->
                    com.spectrafilm.app.silverphase.SpMaterialList(spHost, paper, spMemory, spHost.enabled)
                },
                panelContent = { st -> SpProcessControls(st, spHost, spUi) },
                toolsContent = {
                    val tool = spTool
                    if (tool != null) {
                        // Same flat-section rendering the original panels use.
                        CompositionLocalProvider(LocalFlatSections provides true) { toolContent(tool) }
                    } else {
                        com.spectrafilm.app.silverphase.SpHelp(stringResource(R.string.sp_more_tools_caption))
                        for (section in Stage.entries) {
                            val cats = categoriesOf(section).filter(::toolShown)
                            if (cats.isEmpty()) continue
                            com.spectrafilm.app.silverphase.SpSectionTitle(stringResource(section.labelRes))
                            for (c in cats) {
                                com.spectrafilm.app.silverphase.SpTextAction(stringResource(c.labelRes)) { spToolName = c.name }
                            }
                        }
                    }
                },
            ),
        )
    }
    // =================== END SILVERPHASE PHONE EDITOR (port) ===================

    // Editor window chrome: drop target, window-class decision, the top bar over the body,
    // and the full-screen layers above them (EditorFrame.kt).
    EditorFrame(
        modalOverlayShown = modalOverlayShown,
        acceptDrop = acceptDrop,
        onDropImage = { onImageDrop(it) },
        topBar = { wide ->
            // The phone layout draws its own Silverphase toolbar inside the body.
            if (wide && !viewerUi.chromeHidden) EditorTopBar(
                canUndo = editHistory.canUndo || cropDraft?.touched == true,
                canRedo = editHistory.canRedo && cropDraft?.touched != true,
                onUndo = { doUndo() },
                onRedo = { doRedo() },
                onShowHistory = { historySheet = historyTimeline(editHistory.snapshotState(), snapshotNow()) },
                onPeekOriginal = { viewerVm.onAction(EditorViewerAction.SetOriginalPeek(it)) },
                fading = interacting,
                onOpenSource = {
                    // Open the SAME photo picker the Source panel uses (no app exit).
                    closeCrop()
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onOpenSettings = {
                    closeCrop()
                    checkpointEditorSession()
                    onOpenSettings()
                },
                onOpenAbout = {
                    closeCrop()
                    checkpointEditorSession()
                    onOpenAbout()
                },
                onOpenLibrary = {
                    closeCrop()
                    checkpointEditorSession()
                    // Paste edit in the Library reads this photo's recipe.
                    flushRecipeBeforeLeaving(
                        state, recipeKey, recipeReady, recipeAccess, defaultsJson,
                        rotation, sourceName, hasRecipe, recipeRepo,
                    )
                    onOpenLibrary()
                },
                onPhotoInfo = sourceUri?.let { { openPhotoInfo() } },
                // The canvas tools that used to float on the photo (EditorPreviewRegion.kt).
                hasPhoto = preview != null || gpuProxy != null,
                onCrop = { toolVm.onAction(EditorToolAction.OpenCrop) },
                // The Crop and Frame canvases have no split view, original peek, histogram or
                // clipping overlay, so those toggles rest while either shows.
                compare = viewerUi.compare && !ownCanvas,
                compareEnabled = (preview != null || gpuProxy != null) && !ownCanvas,
                viewTogglesEnabled = (preview != null || gpuProxy != null) && !ownCanvas,
                histogram = viewerUi.histogram && !ownCanvas,
                clipWarnings = viewerUi.clipWarnings && !ownCanvas,
                onToggleCompare = { viewerVm.onAction(EditorViewerAction.ToggleCompare) },
                onToggleHistogram = { viewerVm.onAction(EditorViewerAction.ToggleHistogram) },
                onToggleClipWarnings = { viewerVm.onAction(EditorViewerAction.ToggleClipWarnings) },
                onOpenLoupe = { openMagnifier(0.5f, 0.5f) },
                title = StockCatalog.displayName(ctx, state.filmProfile),
                subtitle = if (state.scanFilm) {
                    stringResource(R.string.editor_preset_scan_positive)
                } else {
                    StockCatalog.displayName(ctx, state.printProfile)
                },
                wide = wide,
                // Hidden, not disabled, when there is nothing to export; a render in flight
                // only defers the tap (see openExportSheetIfAllowed).
                exportVisible = engine != null && sourceRenderAllowed,
                exporting = exportInFlight,
                onExport = { closeCrop(); requestExport() },
                exportSaved = exportDone,
                onShare = { closeCrop(); requestShare() },
            )
        },
        body = { wide, foldingFeature ->
            SideEffect { spNarrow = !wide }
            if (!wide) spPhoneBody() else EditorBody(
                foldingFeature = foldingFeature,
                state = state,
                onParamsChanged = { previewTick++ },
                activeCategory = activeCategory,
                onActiveCategoryChange = openCategory,
                toolShown = ::toolShown,
                wide = wide,
                chromeHidden = viewerUi.chromeHidden,
                panelBackEnabled = !magnifierOpen && !viewerUi.chromeHidden &&
                    !toolUi.anyOverlayOpen && !exportMaskVisible,
                sliderInteraction = sliderInteraction,
                interacting = interacting,
                sliderReadout = sliderReadout,
                panelBackProgress = panelBackProgress,
                exportVisible = engine != null && sourceRenderAllowed,
                exportInFlight = exportInFlight,
                onExport = { closeCrop(); requestExport() },
                onShare = { closeCrop(); requestShare() },
                onOpenCrop = ::toggleCrop,
                toolContent = toolContent,
                previewRegion = previewRegion,
                cropOpen = toolUi.cropOpen,
                cropPanelContent = {
                    CropPanelContent(
                        draft = cropDraft,
                        // Keep what was set so far: the rotated frame re-seeds from the recipe.
                        onRotate = { commitCropDraft(); rotation = rotation.next() },
                    )
                },
            )
        },
        overlays = {
            // --- 100% grain magnifier overlay (EditorZoom.kt) ---
            EditorMagnifierLayer(zoom)

            // The full-screen tools share one wrapper so a held back swipe shrinks whichever
            // is open (see the PredictiveBackHandler above).
            Box(Modifier.fillMaxSize().predictiveBackShrink(overlayBackProgress)) {
                EditorToolOverlays(
                    dispatchers = dispatchers,
                    toolVm = toolVm,
                    ctx = ctx,
                    scope = scope,
                    screen = screen,
                    session = session,
                    toolUi = toolUi,
                    cropBmp = cropBmp,
                    maskGeometryShown = maskGeometryShown,
                    sampleReady = sampleReady,
                    loadSourceCachedForZoom = { loadSourceCachedForZoom(it) },
                    statusState = statusState,
                )
            }

            // --- full-screen export mask, or the finished export's share / snackbar ---
            EditorExportResult(
                exportInFlight = exportInFlight,
                exportDone = exportDone,
                preview = preview,
                onCancelExport = {
                    val running = exportRuntimeState as? ExportRuntimeState.Running
                    if (running != null && ExportWorkRuntime.cancel(running.runId)) {
                        status = ctx.getString(R.string.editor_status_cancelling_export)
                    }
                },
                exportLastShare = exportLastShare,
                exportVm = exportVm,
                ctx = ctx,
                scope = scope,
                snackbarHost = snackbarHost,
                setStatus = { status = it },
            )

            // Everything that reads the export sheet's state sits in its own recompose scope (the
            // composable below reads [exportUiState] itself), so opening the sheet or editing an
            // option recomposes that section and not the whole editor (#270).
            val exportPanel: @Composable (Boolean) -> Unit = { embedded ->
                EditorExportSheetHost(
                    exportUiState = exportUiState,
                    exportVm = exportVm,
                    exportPresetVm = exportPresetVm,
                    ctx = ctx,
                    state = state,
                    preview = preview,
                    engine = engine,
                    sourceUri = sourceUri,
                    sourceKind = sourceKind,
                    legacyStoragePermission = legacyStoragePermission,
                    batchPicker = batchPicker,
                    pairPicker = pairPicker,
                    requestMediaLocationIfMissing = { requestMediaLocationIfMissing() },
                    exportAskingNotificationsOnce = { exportAskingNotificationsOnce(it) },
                    launchEditorExport = { launchEditorExport(it) },
                    setStatus = { status = it },
                    embedded = embedded,
                )
            }
            if (spNarrow) {
                val info = photoInfo
                val history = historySheet
                var displayedInfo by remember { mutableStateOf(info) }
                var displayedHistory by remember { mutableStateOf(history) }
                androidx.compose.runtime.SideEffect {
                    if (info != null) displayedInfo = info
                    if (history != null) displayedHistory = history
                }
                com.spectrafilm.app.silverphase.SpPhoneModal(
                    info != null, spMenuAnchor, spReduceMotion, { photoInfo = null },
                ) { (info ?: displayedInfo)?.let { PhotoInfoContent(it) } }
                com.spectrafilm.app.silverphase.SpPhoneModal(
                    history != null, spMenuAnchor, spReduceMotion, { historySheet = null },
                ) {
                    (history ?: displayedHistory)?.let { (timeline, current) ->
                        EditHistoryContent(timeline, current) { to ->
                            historySheet = null
                            jumpHistory(current, to)
                        }
                    }
                }
                val exportShown = exportUiState.value.sheetOpen
                com.spectrafilm.app.silverphase.SpPhoneModal(
                    exportShown, spExportAnchor, spReduceMotion,
                    { exportVm.onAction(ExportAction.CloseSheet) },
                ) { exportPanel(true) }
            } else {
                exportPanel(false)
            }

            // Recipe restore / status snackbar (above the bottom bar / nav).
            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
            )
        },
    )
}
