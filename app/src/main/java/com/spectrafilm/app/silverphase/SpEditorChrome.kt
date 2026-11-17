/*
 * Silverphase Android port — phone editor chrome and layout. GPLv3.
 * DESIGN.md "普通 iPhone 编辑布局" + compactWorkspace (SilverphaseApp.swift:586-660) +
 * CompactPhoneEditor.swift:
 *  - top: back to library on the left; export and menu only, on the right; no stage title,
 *    no film/paper banner (undo, redo, fit, reset, settings and about live in the menu);
 *  - status overlay under the toolbar, 16dp from the left and 12dp below it: file name,
 *    original pixel size, preview/original/processing state;
 *  - the photo is fitted to the work area (aspect kept, never cropped); panels never change
 *    its fit rectangle;
 *  - seven fixed process stages in a 56dp bottom control; the floating panel above them;
 *  - press-and-hold ~250ms on the photo (moving < 8dp) shows the original until release.
 *
 * Motion: the panel grows out of the tab that opened it and folds back into the selected tab
 * (SpMorph, .smooth(0.28)); the material browser grows out of the 当前胶片/当前相纸 card; the
 * Android tools drawer and every menu grow out of the menu button. Swipe-down and predictive
 * back drive the fold directly and hand their release velocity to the spring.
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.*
import com.spectrafilm.app.editor.*
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.VisibilityThreshold
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.togetherWith
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.spectrafilm.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Glass-backed toolbar group: back alone on the left; export + menu share one capsule on the right. */
@Composable
internal fun SpEditorTopBar(
    onBack: () -> Unit,
    exportVisible: Boolean,
    exportEnabled: Boolean,
    onExport: () -> Unit,
    onMenu: (SpOrigin) -> Unit,
    onMenuOrigin: (SpOrigin) -> Unit,
    onExportOrigin: (SpOrigin) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOrigin by remember { mutableStateOf<SpOrigin?>(null) }
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(SpTopInsets).padding(top = SpTopBreathing).padding(horizontal = SpDesign.gutter).height(SpDesign.hitSize + 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.spChromeSurface(shape = CircleShape)) {
            SpChromeButton(spS(R.string.sp_back_to_library), SpGlyph.BACK, onClick = onBack)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.spChromeSurface(shape = RoundedCornerShape(24.dp))) {
            if (exportVisible) SpChromeButton(
                spS(R.string.sp_export), SpGlyph.EXPORT,
                modifier = Modifier.spOriginOf(onExportOrigin),
                enabled = exportEnabled, onClick = onExport,
            )
            SpChromeButton(
                spS(R.string.sp_menu), SpGlyph.MENU,
                modifier = Modifier.spOriginOf({ menuOrigin = it; onMenuOrigin(it) }),
            ) { menuOrigin?.let(onMenu) }
        }
    }
}

/**
 * C03: the top bar keeps the status-bar and cut-out height even when full-screen editing hides
 * the status bar, so it never hugs the screen edge; plus a little breathing room.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal val SpTopInsets: WindowInsets
    @Composable get() = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
internal val SpTopBreathing = 6.dp

/** The status overlay: file name, pixel size, and state, in 11dp/12dp secondary text. */
@Composable
internal fun SpStatusOverlay(
    fileName: String?, pixelSize: String?, state: String?, modifier: Modifier = Modifier,
    /** Export progress 0…1 while an export runs (MobileOperation's accent ProgressView), else null. */
    progress: Float? = null,
) {
    if (fileName == null && state == null) return
    Column(modifier.padding(start = SpDesign.contentInset, top = SpDesign.gutter), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (fileName != null) Text(fileName, style = SpDesign.label, color = SpStyle.primary.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = listOfNotNull(pixelSize, state).joinToString(" · ")
        // C05: state changes (读取中 → 处理中 → 适屏预览) cross-fade instead of snapping.
        androidx.compose.animation.AnimatedContent(
            targetState = line,
            transitionSpec = {
                // Only the percentage ticking: no fade, or the line would flicker.
                if (initialState.substringBefore('%').dropLastWhile { it.isDigit() } ==
                    targetState.substringBefore('%').dropLastWhile { it.isDigit() }) {
                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                } else {
                    androidx.compose.animation.fadeIn(SpMotion.easeOut(220)) togetherWith
                        androidx.compose.animation.fadeOut(SpMotion.easeOut(160))
                }
            },
            label = "spStatus",
        ) { text ->
            if (text.isNotEmpty()) Text(text, style = SpDesign.caption, color = SpStyle.secondaryText, maxLines = 1)
        }
        // C05: a thin accent bar that glides with the export instead of only a ticking number.
        // Holds the last fraction (no state write in composition) so the bar fades out full.
        val held = remember { floatArrayOf(0f) }
        if (progress != null) held[0] = progress
        val lastProgress = held[0]
        androidx.compose.animation.AnimatedVisibility(
            progress != null,
            enter = androidx.compose.animation.expandVertically(SpMotion.smooth028(androidx.compose.ui.unit.IntSize.VisibilityThreshold)) +
                androidx.compose.animation.fadeIn(SpMotion.easeOut(200)),
            exit = androidx.compose.animation.fadeOut(SpMotion.easeOut(220)) +
                androidx.compose.animation.shrinkVertically(SpMotion.smooth028(androidx.compose.ui.unit.IntSize.VisibilityThreshold)),
        ) {
            val shown by androidx.compose.animation.core.animateFloatAsState(
                lastProgress.coerceIn(0f, 1f), SpMotion.smooth030(0.001f), label = "exportProgress",
            )
            Box(
                Modifier.padding(top = 4.dp).width(140.dp).height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp)).background(SpStyle.well),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(SpStyle.accent, RoundedCornerShape(1.5.dp)))
            }
        }
    }
}

/**
 * Hold-to-compare on the canvas without stealing pinch/pan/double-tap from it: observed in the
 * Initial pass and never consumed. Fires after 250ms still (≤ 8dp of travel), ends on lift.
 */
internal fun Modifier.spHoldToCompare(enabled: Boolean, onPeek: (Boolean) -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val slop = 8.dp.toPx()
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var peeking = false
                val timer = launch { delay(250); peeking = true; onPeek(true) }
                try {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.size > 1) { timer.cancel(); break }
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (!peeking && (ch.position - down.position).getDistance() > slop) { timer.cancel(); break }
                    }
                } finally {
                    timer.cancel()
                    if (peeking) onPeek(false)
                }
            }
        }
    }

/** Swallows touches on chrome that is faded out (hidden by a tap on the photo). */
private fun Modifier.spBlockTouches(block: Boolean): Modifier =
    if (!block) this else this.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
    }

/** Predictive back that drives a morph and hands its release velocity to the spring. */
@Composable
private fun SpMorphBack(enabled: Boolean, morph: SpMorphState, onClosed: () -> Unit) {
    val closed by rememberUpdatedState(onClosed)
    PredictiveBackHandler(enabled) { events ->
        var lastP = morph.gestureProgress
        var lastT = System.nanoTime()
        var v = 0f
        try {
            events.collect { e ->
                val p = 1f - e.progress
                val t = System.nanoTime()
                val dt = (t - lastT) / 1e9f
                if (dt > 0.004f) { v = 0.6f * v + 0.4f * ((p - lastP) / dt); lastP = p; lastT = t }
                morph.track(p)
            }
            morph.close(minOf(v, 0f)) { closed() }
        } catch (e: CancellationException) {
            morph.open(initialVelocity = maxOf(v, 0f))
            throw e
        }
    }
}

/** Contents of the morph-hosted panels the phone editor shows. */
internal class SpPhoneSlots(
    val canvas: @Composable () -> Unit,
    val status: @Composable () -> Unit,
    val empty: (@Composable () -> Unit)?,
    val materialList: @Composable (paper: Boolean) -> Unit,
    val panelContent: @Composable ColumnScope.(SpStage) -> Unit,
    val toolsContent: @Composable ColumnScope.() -> Unit,
)

/**
 * The phone editor body. [stage] is the open stage (null = panel folded). The material list
 * opens over the panel body when [materialsOpenForPaper] is non-null; the Android tools drawer
 * when [toolsOpen].
 */
@Composable
internal fun SpPhoneEditorBody(
    stage: SpStage?,
    onStage: (SpStage) -> Unit,
    onClosePanel: () -> Unit,
    onResetStage: (SpStage) -> Unit,
    tabsEnabled: Boolean,
    chromeHidden: Boolean,
    backEnabled: Boolean,
    reduceMotion: Boolean,
    reduceTransparency: Boolean,
    materialsOpenForPaper: Boolean?,
    materialOrigin: SpOrigin?,
    onMaterialsDone: () -> Unit,
    toolsOpen: Boolean,
    toolsTitle: String,
    toolsBack: (() -> Unit)?,
    onToolsClose: () -> Unit,
    onBack: () -> Unit,
    exportVisible: Boolean,
    exportEnabled: Boolean,
    onExport: () -> Unit,
    menuItems: () -> List<SpMenuItem>,
    slots: SpPhoneSlots,
    onMenuOrigin: (SpOrigin) -> Unit = {},
    onExportOrigin: (SpOrigin) -> Unit = {},
) {
    SpText.bind(LocalContext.current.resources)
    val density = LocalDensity.current
    val overlayMorph = rememberSpMorphState(reduceMotion)
    val overlay = remember(overlayMorph) { SpOverlayController(overlayMorph) }
    val panelMorph = rememberSpMorphState(reduceMotion)
    val materialsMorph = rememberSpMorphState(reduceMotion)
    val toolsMorph = rememberSpMorphState(reduceMotion)
    val tabOrigins = remember { arrayOfNulls<SpOrigin>(SpStage.entries.size) }
    var menuOrigin by remember { mutableStateOf<SpOrigin?>(null) }
    var shownStage by remember { mutableStateOf(stage ?: SpStage.FILM) }
    var shownPaper by remember { mutableStateOf(materialsOpenForPaper ?: false) }
    var fraction by rememberSaveable { mutableFloatStateOf(0.42f) }
    val currentClose by rememberUpdatedState(onClosePanel)
    val currentMaterialsDone by rememberUpdatedState(onMaterialsDone)
    val currentToolsClose by rememberUpdatedState(onToolsClose)
    val reduceMotionState = rememberUpdatedState(reduceMotion)
    fun reduceMotionNow() = reduceMotionState.value
    var panelGeometry by remember { mutableStateOf<SpPanelGeo?>(null) }

    // Layoutlib screenshots have no running effects: open what the arguments ask for at once.
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        remember(stage, materialsOpenForPaper) {
            if (stage != null) { shownStage = stage; panelMorph.open() }
            if (materialsOpenForPaper != null) { shownPaper = materialsOpenForPaper; materialsMorph.open() }
            0
        }
    }
    LaunchedEffect(stage) {
        if (stage != null) { shownStage = stage; panelMorph.open(tabOrigins[stage.ordinal]) }
        else if (panelMorph.isOpen) panelMorph.close()
    }
    LaunchedEffect(materialsOpenForPaper) {
        if (materialsOpenForPaper != null) { shownPaper = materialsOpenForPaper; materialsMorph.open(materialOrigin) }
        else if (materialsMorph.isOpen) materialsMorph.close()
    }
    LaunchedEffect(toolsOpen) {
        if (toolsOpen) toolsMorph.open(menuOrigin) else if (toolsMorph.isOpen) toolsMorph.close()
    }
    // Back: the top-most open thing folds first (menu > materials > tools > panel).
    SpMorphBack(backEnabled && panelMorph.isOpen && !materialsMorph.isOpen && !overlay.morph.isOpen, panelMorph) { currentClose() }
    SpMorphBack(backEnabled && materialsMorph.isOpen && !overlay.morph.isOpen, materialsMorph) { currentMaterialsDone() }
    SpMorphBack(backEnabled && toolsMorph.isOpen && toolsBack == null && !overlay.morph.isOpen, toolsMorph) { currentToolsClose() }
    if (toolsBack != null) androidx.activity.compose.BackHandler(backEnabled && toolsMorph.isOpen && !overlay.morph.isOpen) { toolsBack() }

    // Chrome fade for the tap-to-hide toggle, read in graphicsLayer only.
    val chromeAlpha = remember { Animatable(if (chromeHidden) 0f else 1f) }
    LaunchedEffect(chromeHidden, reduceMotion) {
        SpMotion.animateOpacity(chromeAlpha, if (chromeHidden) 0f else 1f, reduceMotion, 180)
    }
    // C03 fix: the chrome layers fill the screen, so a touch blocker on them also swallowed the
    // tap on the photo that should bring the chrome back (hidden forever). Hidden chrome is now
    // simply not placed once its fade has finished, so it takes no hit tests and the photo
    // underneath gets the tap again.
    val hiddenState = rememberUpdatedState(chromeHidden)
    val chrome = Modifier.graphicsLayer { alpha = chromeAlpha.value }.layout { m, c ->
        val pl = m.measure(c)
        layout(pl.width, pl.height) {
            if (!hiddenState.value || chromeAlpha.value > 0.01f) pl.place(0, 0)
        }
    }

    CompositionLocalProvider(LocalSpOverlay provides overlay, LocalSpReduceMotion provides reduceMotion) {
        BoxWithConstraints(Modifier.fillMaxSize().background(SpStyle.canvas)) {
            val tabsZone = SpDesign.tabsHeight + SpDesign.gutter * 2
            // C05: height of the file-name / size / state lines under the top bar. The photo is
            // fitted below them, so an open panel no longer pushes the picture up over the text.
            val statusHeightPx = remember { androidx.compose.runtime.mutableIntStateOf(0) }
            // Fit the photo inside the space left by the open editor panel.
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .windowInsetsPadding(SpTopInsets)
                    .padding(top = SpDesign.hitSize + 8.dp + SpTopBreathing, bottom = tabsZone)
                    .layout { m, c ->
                        val inset = panelGeometry?.let { g ->
                            val progress = if (reduceMotionNow()) if (panelMorph.isOpen) 1f else 0f else panelMorph.progress.value
                            val toolsProgress = if (reduceMotionNow()) if (toolsMorph.isOpen) 1f else 0f else toolsMorph.progress.value
                            maxOf(g.canvasInset(g.height(fraction), progress),
                                g.canvasInset(g.maxH, toolsProgress)) * chromeAlpha.value
                        } ?: 0f
                        val occupied = inset.roundToInt().coerceIn(0, c.maxHeight)
                        val top = (statusHeightPx.intValue * chromeAlpha.value).roundToInt()
                            .coerceIn(0, (c.maxHeight - occupied).coerceAtLeast(0))
                        val pl = m.measure(c.copy(minHeight = 0, maxHeight = c.maxHeight - occupied - top))
                        layout(c.maxWidth, c.maxHeight) { pl.place(0, top) }
                    },
            ) { slots.canvas() }
            slots.empty?.let { e -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { e() } }
            Column(Modifier.fillMaxSize().then(chrome)) {
                SpEditorTopBar(
                    onBack = onBack, exportVisible = exportVisible, exportEnabled = exportEnabled, onExport = onExport,
                    onMenu = { o -> overlay.showMenu(o, menuItems()) }, onMenuOrigin = { menuOrigin = it; onMenuOrigin(it) }, onExportOrigin = onExportOrigin,
                )
                Box(Modifier.onSizeChanged { statusHeightPx.intValue = it.height }) { slots.status() }
            }
            // Work area = inside the safe drawing insets (gesture bar, cut-outs).
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).then(chrome)) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Everything below that depends on the panel height reads [fraction] only in
                    // layout / draw lambdas (panelHeightPx()), so dragging the height re-lays out
                    // the panel without recomposing the editor (no per-frame recomposition).
                    val layout0 = SpPhoneLayout.of(maxWidth, maxHeight, 0.42f, showsPhotos = false)
                    val hPx = with(density) { maxHeight.toPx() }
                    val wPx = with(density) { maxWidth.toPx() }
                    val g = with(density) { SpDesign.gutter.toPx() }
                    val maxH = with(density) { layout0.maxPanelHeight.toPx() }
                    val panelBottomPx = with(density) { layout0.panelBottom.toPx() }
                    val floorPx = minOf(with(density) { 176.dp.toPx() }, maxH)
                    val chromePx = with(density) { SpPanelChromeHeight.toPx() }
                    val tabsZonePx = with(density) { tabsZone.toPx() }
                    val toolsRect = Rect(Offset(g, panelBottomPx - maxH), Size(wPx - 2 * g, maxH))
                    val minFraction = maxOf(0.25f, floorPx / hPx)
                    val maxFraction = maxOf(minFraction, minOf(SpDesign.panelMaxFraction, maxH / hPx))
                    // Same formula as SpPhoneLayout.of, in px, evaluated where it is read.
                    val geo by rememberUpdatedState(SpPanelGeo(hPx, wPx, g, maxH, panelBottomPx, floorPx, chromePx, minFraction, maxFraction, tabsZonePx))
                    androidx.compose.runtime.SideEffect { panelGeometry = geo }
                    fun panelHeightPx(): Float = geo.height(fraction)
                    fun panelRect(): Rect = geo.rect(fraction)
                    val panelDrag = remember(panelMorph) {
                        SpPanelDrag(
                            panelMorph,
                            resize = { dy ->
                                val gg = geo
                                val f0 = fraction.coerceIn(gg.minFraction, gg.maxFraction)
                                val f1 = (f0 - dy / gg.h).coerceIn(gg.minFraction, gg.maxFraction)
                                fraction = f1
                                dy - (f0 - f1) * gg.h
                            },
                            dismissDistancePx = { geo.height(fraction) },
                            onDismissed = { currentClose() },
                        )
                    }
                    val toolsDrag = remember(toolsMorph) {
                        SpPanelDrag(toolsMorph, resize = { it }, dismissDistancePx = { geo.maxH }, onDismissed = { currentToolsClose() })
                    }
                    // The material list folds back into its card on a pull past its top, instead of
                    // the pull reaching the panel and folding the whole panel (CODE_REVIEW #5).
                    val materialsDrag = remember(materialsMorph) {
                        SpPanelDrag(
                            materialsMorph, resize = { it },
                            dismissDistancePx = { geo.height(fraction) - geo.chromePx },
                            onDismissed = { currentMaterialsDone() },
                        )
                    }
                    // The process panel.
                    SpMorphSurface(panelMorph, ::panelRect, with(density) { 24.dp.toPx() }, reduceTransparency) {
                        val title = when {
                            materialsMorph.isOpen -> spS(if (shownPaper) R.string.sp_choose_paper else R.string.sp_choose_film)
                            else -> shownStage.title
                        }
                        SpFloatingPanel(
                            title = title,
                            drag = panelDrag,
                            actions = {
                                if (materialsMorph.isOpen) SpHeaderTextButton(spS(R.string.sp_done)) {
                                    materialsMorph.close { currentMaterialsDone() }
                                } else {
                                    var actionsOrigin by remember { mutableStateOf<SpOrigin?>(null) }
                                    SpChromeButton(
                                        spS(R.string.sp_process_actions), SpGlyph.MENU,
                                        modifier = Modifier.spOriginOf({ actionsOrigin = it }),
                                    ) {
                                        actionsOrigin?.let { o ->
                                            overlay.showMenu(o, listOf(SpMenuItem(spS(R.string.sp_reset_this_stage)) { onResetStage(shownStage) }))
                                        }
                                    }
                                    SpChromeButton(spS(R.string.sp_collapse_panel), SpGlyph.CHEVRON_DOWN) {
                                        panelMorph.close { currentClose() }
                                    }
                                }
                            },
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                // Stage content cross-fades on a stage switch (.easeInOut 0.18, .id(stage).transition(.opacity)).
                                // C05: plus a short drift toward the side of the newly chosen tab
                                // (Reduce Motion keeps the plain iOS cross-fade).
                                androidx.compose.animation.AnimatedContent(
                                    shownStage, label = "stage",
                                    transitionSpec = {
                                        val fade = androidx.compose.animation.fadeIn(SpMotion.easeInOut(180)) togetherWith
                                            androidx.compose.animation.fadeOut(SpMotion.easeInOut(180))
                                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                        val drift = (androidx.compose.animation.slideInHorizontally(SpMotion.smooth028(IntOffset.VisibilityThreshold)) { w -> dir * w / 14 } +
                                            androidx.compose.animation.fadeIn(SpMotion.easeInOut(180))) togetherWith
                                            (androidx.compose.animation.slideOutHorizontally(SpMotion.smooth028(IntOffset.VisibilityThreshold)) { w -> -dir * w / 14 } +
                                                androidx.compose.animation.fadeOut(SpMotion.easeInOut(150)))
                                        (if (reduceMotion) fade else drift) using androidx.compose.animation.SizeTransform(clip = false)
                                    },
                                    modifier = Modifier.fillMaxSize().graphicsLayer {
                                        alpha = 1f - materialsMorph.progress.value.coerceIn(0f, 1f) * materialsMorph.fade.value
                                    },
                                ) { st ->
                                    Column(
                                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                            .padding(horizontal = SpDesign.contentInset).padding(top = 8.dp, bottom = SpDesign.contentInset),
                                    ) {
                        // C03: Material-based stage bodies (片边, 构图 …) draw Text without a colour,
                        // which fell back to LocalContentColor's default black on the dark glass.
                        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides SpStyle.primary) {
                            slots.panelContent(this, st)
                        }
                    }
                                }
                                // The material browser grows out of the 当前胶片 / 当前相纸 card.
                                SpMorphSurface(
                                    materialsMorph,
                                    { Rect(Offset.Zero, Size(geo.wPx - 2 * geo.g, (panelHeightPx() - geo.chromePx).coerceAtLeast(1f))) },
                                    0f, reduceTransparency, flushWhenOpen = true,
                                ) {
                                    Box(Modifier.fillMaxSize().nestedScroll(materialsDrag.nestedScroll)) { slots.materialList(shownPaper) }
                                }
                            }
                        }
                    }

                    // The Android tools drawer (Spektrafilm-for-Android tools with no Silverphase screen).
                    SpMorphSurface(toolsMorph, { toolsRect }, with(density) { 24.dp.toPx() }, reduceTransparency) {
                        SpFloatingPanel(
                            title = toolsTitle,
                            drag = toolsDrag,
                            actions = {
                                if (toolsBack != null) SpHeaderTextButton(spS(R.string.sp_back), toolsBack)
                                SpChromeButton(spS(R.string.sp_collapse_panel), SpGlyph.CHEVRON_DOWN) { toolsMorph.close { currentToolsClose() } }
                            },
                        ) {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .padding(horizontal = SpDesign.contentInset).padding(top = 8.dp, bottom = SpDesign.contentInset),
                            ) {
                                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides SpStyle.primary) {
                                    slots.toolsContent(this)
                                }
                            }
                        }
                    }

                    SpProcessTabs(
                        stage = stage, enabled = tabsEnabled, reduceMotion = reduceMotion,
                        onSelect = { st ->
                            // Re-selecting re-opens (iOS repeatedTap); a fold in progress reverses from where it is.
                            panelMorph.open(tabOrigins[st.ordinal]); shownStage = st; onStage(st)
                        },
                        onSegmentOrigin = { st, o -> tabOrigins[st.ordinal] = o; if (st == shownStage && !panelMorph.isOpen) panelMorph.origin = o },
                        modifier = Modifier.offset { IntOffset(0, layout0.tabsTop.roundToPx()) }.padding(horizontal = SpDesign.gutter),
                    )
                }
            }
            SpOverlayHost(overlay)
        }
    }
}

/** iOS emptyEditor: glyph, title, caption and the prominent 打开照片 button. */
@Composable
internal fun SpEmptyEditor(onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpGlyphIcon(SpGlyph.PHOTO, SpStyle.secondary, Modifier.size(27.dp))
        Text(spS(R.string.sp_open_photo_to_start), style = SpDesign.panelTitle)
        Text(spS(R.string.sp_film_dev_print), style = SpDesign.caption, color = SpStyle.secondary)
        Box(
            Modifier.width(144.dp).height(SpDesign.hitSize).spPressable(onClick = onOpen).background(SpStyle.accent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(spS(R.string.sp_open_photo), style = SpDesign.selectedValue.copy(color = SpStyle.ink)) }
    }
}

/** Panel geometry of the phone work area, in px; [height] is SpPhoneLayout.of's panel height. */
internal data class SpPanelGeo(
    val h: Float, val wPx: Float, val g: Float, val maxH: Float, val panelBottomPx: Float,
    val floorPx: Float, val chromePx: Float, val minFraction: Float, val maxFraction: Float, val tabsZonePx: Float,
) {
    fun height(fraction: Float): Float = minOf(maxH, maxOf(floorPx, h * fraction.coerceIn(0.25f, 0.72f)))
    fun canvasInset(panelHeight: Float, progress: Float): Float =
        (h - tabsZonePx - (panelBottomPx - panelHeight) + g).coerceAtLeast(0f) *
            progress.coerceIn(0f, 1f)

    fun rect(fraction: Float): Rect {
        val ph = height(fraction)
        return Rect(Offset(g, panelBottomPx - ph), Size(wPx - 2 * g, ph))
    }
}
