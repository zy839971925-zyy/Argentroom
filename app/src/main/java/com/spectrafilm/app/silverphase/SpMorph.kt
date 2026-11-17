/*
 * Silverphase Android port — "一镜到底" container morph. GPLv3.
 *
 * A panel, drawer, menu or material browser grows out of the control that opened it and
 * shrinks back into it when it closes (哪里来回哪里去). Position, size, corner radius and
 * the surface's opacity are interpolated together from ONE progress value; the content is
 * laid out once at its final size and only clipped by the growing outline, so text is never
 * scaled or re-flowed while the frame moves, and it fades in after the frame has opened
 * ~40% (and fades out before the frame has shrunk back to ~40%).
 *
 * Why not SharedTransitionLayout/sharedBounds (verified present in compose-animation 1.12.1,
 * BOM 2026.09.00): its bounds animation is driven by the visibility Transition, and
 * SeekableTransitionState.animateTo(target, FiniteAnimationSpec) has no initial-velocity
 * parameter, so a swipe-down or predictive-back release cannot hand its velocity to the
 * finishing spring. One Animatable keeps the velocity across every interruption (open while
 * closing continues from the current value and velocity), which is what the motion brief
 * asks for. Curves are the iOS `.smooth(0.28)` spring (dampingRatio 1, stiffness 503.5).
 *
 * Every animated value is read in draw/layout/graphicsLayer lambdas only, never in
 * composition: a morph frame does not recompose the panel.
 *
 * Reduce Motion (system animator scale 0): geometry snaps to its end and a 0.2 s opacity
 * fade carries the change, run at real time (SpMotion.RealTime), as iOS does with
 * accessibilityReduceMotion.
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.ui.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Where a morph comes from: a control's bounds in root coordinates and its corner radius (px). */
internal data class SpOrigin(val rect: Rect, val radiusPx: Float)

/** Captures a control's root bounds into [into] so a morph can start from it. */
internal fun Modifier.spOriginOf(into: (SpOrigin) -> Unit, radiusPx: Float = -1f): Modifier =
    onGloballyPositioned { c: LayoutCoordinates ->
        val r = c.boundsInRoot()
        into(SpOrigin(r, if (radiusPx >= 0f) radiusPx else minOf(r.width, r.height) / 2f))
    }

@Stable
internal class SpMorphState(private val scope: CoroutineScope) {
    /** Geometry progress: 0 = folded into its origin, 1 = fully open. */
    val progress = Animatable(0f)
    /** Opacity of the whole morph; < 1 only during a Reduce Motion fade. */
    val fade = Animatable(0f)
    /** Where the morph should be heading. */
    var isOpen by mutableStateOf(false)
        private set
    var origin by mutableStateOf<SpOrigin?>(null)
    var reduceMotion by mutableStateOf(false)
    /** Composed while open or still folding away; changes only at the edges, not per frame. */
    val visible by derivedStateOf { isOpen || progress.value > 0f || fade.value > 0f }
    /** Fully open and at rest (content may take focus, etc.). */
    val settled by derivedStateOf { isOpen && progress.value >= 1f && !progress.isRunning }

    /**
     * The value a gesture last asked for, written synchronously. track() hands the snap to a
     * coroutine; reading progress.value right after it would give the pre-snap value and drop
     * the delta of a second event that arrives before the coroutine runs.
     */
    private var tracked = Float.NaN

    /** Layoutlib / preview rendering: no frame clock to animate on, so morphs land at once. */
    internal var instant = false

    fun open(from: SpOrigin? = origin, initialVelocity: Float = progress.velocity) {
        if (from != null) origin = from
        isOpen = true
        tracked = Float.NaN
        if (instant) { scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(1f); fade.snapTo(1f) }; return }
        scope.launch {
            if (reduceMotion) {
                progress.snapTo(1f)
                withContext(SpMotion.RealTime) { fade.animateTo(1f, SpMotion.easeOut(SpMotion.REDUCED_FADE_MS)) }
            } else {
                fade.snapTo(1f)
                progress.animateTo(1f, SpMotion.smooth028(0.001f), initialVelocity)
            }
        }
    }

    fun close(initialVelocity: Float = progress.velocity, onClosed: () -> Unit = {}) {
        isOpen = false
        tracked = Float.NaN
        scope.launch {
            if (reduceMotion) {
                withContext(SpMotion.RealTime) { fade.animateTo(0f, SpMotion.easeOut(SpMotion.REDUCED_FADE_MS)) }
                progress.snapTo(0f)
            } else {
                progress.animateTo(0f, SpMotion.smooth028(0.001f), initialVelocity)
                fade.snapTo(0f)
            }
            if (!isOpen) onClosed()
        }
    }

    /** Close instantly (source change, leaving the editor). */
    fun dismissNow() {
        isOpen = false
        tracked = Float.NaN
        scope.launch { progress.snapTo(0f); fade.snapTo(0f) }
    }

    /** A gesture drives the morph directly (swipe-down, predictive back). [p] in 0..1. */
    fun track(p: Float) {
        val v = p.coerceIn(0f, 1f)
        tracked = v
        // Undispatched: the snap lands in the same frame as the touch event whenever the
        // Animatable's mutex is free (it is, except right after a running spring is cancelled).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (reduceMotion) fade.snapTo(v) else progress.snapTo(v)
        }
    }

    /** Current gesture-visible progress (the last tracked value while a gesture runs). */
    val gestureProgress: Float get() = when {
        !tracked.isNaN() -> tracked
        reduceMotion -> fade.value
        else -> progress.value
    }

    /**
     * Ends a gesture. [velocity] in progress units per second (negative = closing). The finishing
     * spring starts from the release velocity, so there is no speed step at the hand-off.
     */
    fun release(velocity: Float, onClosed: () -> Unit) {
        val p = gestureProgress
        val dismiss = velocity < -1.5f || (p < 0.6f && velocity < 0.75f)
        if (dismiss) close(velocity, onClosed) else open(initialVelocity = velocity)
    }
}

@Composable
internal fun rememberSpMorphState(reduceMotion: Boolean): SpMorphState {
    val scope = rememberCoroutineScope()
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    val s = remember { SpMorphState(scope).also { it.reduceMotion = reduceMotion; it.instant = inspection } }
    // Written after composition, never during it (no state write inside the composing frame).
    SideEffect { s.reduceMotion = reduceMotion }
    return s
}

/** Content fades in over the last 60% of the opening (and out over the first 60% of closing). */
private fun contentAlpha(p: Float) = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
/** The glass surface is opaque almost at once, so the morph reads as one object. */
private fun surfaceAlpha(p: Float) = (p / 0.18f).coerceIn(0f, 1f)

/**
 * Hosts one morph inside a full-size overlay box. [target] gives the open rectangle in this
 * box's coordinates; it is a function read only in the layout and draw phases, so a target that
 * follows a gesture (the panel's drag-to-resize) re-lays out and redraws the surface without
 * recomposing it. [targetRadius] is its corner radius in px. The origin (root coordinates) is
 * translated into this box with the box's own root position.
 */
@Composable
internal fun BoxScope.SpMorphSurface(
    state: SpMorphState,
    target: () -> Rect,
    targetRadius: Float,
    reduceTransparency: Boolean = false,
    surface: Boolean = true,
    /** Nested in another surface (the material browser in the panel): once open, its rim and
     *  shadow fade out so it reads as the same panel, not a second sheet stacked on it. */
    flushWhenOpen: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!state.visible) return
    // State read only in draw: a moved host redraws, it never recomposes.
    var hostOffset by remember { mutableStateOf(Offset.Zero) }
    val path = remember { Path() }
    // Reads progress only inside the draw / layer lambdas below.
    fun currentRect(): Pair<Rect, Float> {
        val p = state.progress.value.coerceIn(0f, 1f)
        val t = target()
        val o = state.origin ?: return t to targetRadius
        val from = o.rect.translate(-hostOffset.x, -hostOffset.y)
        return lerp(from, t, p) to lerp(o.radiusPx, targetRadius, p)
    }
    // C03: 0xF5 (was 0xEB) so a bright photo (film-strip sprockets) never shows through the panel text.
    val fill = if (reduceTransparency) SpStyle.canvas else SpStyle.glassFill
    Box(
        Modifier
            .matchParentSize()
            .onGloballyPositioned { hostOffset = it.positionInRoot() }
            .graphicsLayer { alpha = state.fade.value }
            .drawWithContent {
                val (r, rad) = currentRect()
                val p = state.progress.value.coerceIn(0f, 1f)
                val rr = RoundRect(r, CornerRadius(rad))
                path.reset(); path.addRoundRect(rr)
                if (surface) {
                    val sa = surfaceAlpha(p)
                    val edge = if (flushWhenOpen) sa * (1f - ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)) else sa
                    // Soft drop shadow, painted (no blur pass), so the morph never re-blurs the photo.
                    for (i in 1..3) {
                        val g = i * 3.dp.toPx()
                        drawRoundRect(
                            SpStyle.ink.copy(alpha = 0.10f * edge), r.topLeft - Offset(g, g * 0.4f),
                            Size(r.width + 2 * g, r.height + 2 * g), CornerRadius(rad + g),
                        )
                    }
                    drawPath(path, fill.copy(alpha = fill.alpha * sa))
                    clipPath(path) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to SpStyle.primary.copy(alpha = 0.07f * edge), 0.35f to SpStyle.primary.copy(alpha = 0.015f * edge),
                                1f to Color.Transparent, startY = r.top, endY = r.bottom,
                            ),
                            topLeft = r.topLeft, size = r.size,
                        )
                    }
                    drawPath(path, SpStyle.primary.copy(alpha = 0.14f * edge), style = Stroke(0.75.dp.toPx()))
                }
                clipPath(path) { this@drawWithContent.drawContent() }
            },
    ) {
        Box(
            Modifier
                .layout { m, c ->
                    val t = target()
                    val w = t.width.roundToInt().coerceAtLeast(1)
                    val h = t.height.roundToInt().coerceAtLeast(1)
                    val pl = m.measure(Constraints.fixed(w, h))
                    layout(c.maxWidth, c.maxHeight) { pl.place(t.left.roundToInt(), t.top.roundToInt()) }
                }
                .graphicsLayer { alpha = if (state.reduceMotion) 1f else contentAlpha(state.progress.value.coerceIn(0f, 1f)) }
                // While folding away the content takes no touches (they belong to what is underneath).
                .then(if (state.isOpen) Modifier else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } }
                }),
            content = content,
        )
    }
}
