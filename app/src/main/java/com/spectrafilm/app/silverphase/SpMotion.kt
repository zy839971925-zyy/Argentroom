/*
 * Silverphase Android port — motion vocabulary, Reduce Motion and haptics. GPLv3.
 * Every curve here is the Compose equivalent of a SwiftUI curve used by Silverphase v1.2.5
 * (worklog/MOTION_SPEC.md):
 *   .smooth(duration: d)  -> spring(dampingRatio = 1, stiffness = (2π/d)²)   (0.28 -> 503.5, 0.30 -> 438.6)
 *   .easeOut(duration: d) -> tween(d, CubicBezierEasing(0, 0, 0.58, 1))
 *   .easeInOut(duration:d)-> tween(d, CubicBezierEasing(0.42, 0, 0.58, 1))
 * No Compose default spring(), no Material MotionScheme, no FastOutSlowInEasing.
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.editor.*
import com.spectrafilm.app.library.*
import com.spectrafilm.app.ui.*
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.withContext
import kotlin.math.PI

internal object SpMotion {
    /** SwiftUI default easeOut / easeInOut control points. */
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** stiffness of `.smooth(duration: d)` with mass 1. */
    fun smoothStiffness(d: Double): Float = ((2 * PI / d) * (2 * PI / d)).toFloat()
    val SMOOTH_028 = smoothStiffness(0.28) // 503.5
    val SMOOTH_030 = smoothStiffness(0.30) // 438.6

    /** `.smooth(duration: 0.28)`: panel, material browser, menus. */
    fun <T> smooth028(visibilityThreshold: T? = null) =
        spring(dampingRatio = 1f, stiffness = SMOOTH_028, visibilityThreshold = visibilityThreshold)
    /** `.smooth(duration: 0.30)`: notification cards. */
    fun <T> smooth030(visibilityThreshold: T? = null) =
        spring(dampingRatio = 1f, stiffness = SMOOTH_030, visibilityThreshold = visibilityThreshold)

    fun <T> easeOut(ms: Int): FiniteAnimationSpec<T> = tween(ms, easing = EaseOut)
    fun <T> easeInOut(ms: Int): FiniteAnimationSpec<T> = tween(ms, easing = EaseInOut)
    fun <T> snap(): FiniteAnimationSpec<T> = tween(0)

    fun routeTransition(reduce: Boolean, back: Boolean = false, grow: Boolean = false): ContentTransform {
        val fade = if (reduce) REDUCED_FADE_MS else 180
        val enter = fadeIn(easeOut(fade))
        val exit = fadeOut(easeOut(fade))
        if (reduce) return enter togetherWith exit
        if (grow) return (scaleIn(smooth030(), initialScale = 0.94f) + enter) togetherWith exit

        val direction = if (back) -1 else 1
        val slide = smooth028(IntOffset.VisibilityThreshold)
        val outgoing = if (back) slideOutHorizontally(slide) { it / 5 } + exit else exit
        return (slideInHorizontally(slide) { direction * it / 5 } + enter) togetherWith outgoing
    }

    /** Opacity transitions keep a short real-time fade when Android disables animation. */
    suspend fun animateOpacity(
        animation: Animatable<Float, AnimationVector1D>,
        target: Float,
        reduce: Boolean,
        durationMillis: Int,
    ) {
        if (reduce) withContext(RealTime) {
            animation.animateTo(target, easeOut(REDUCED_FADE_MS))
        } else {
            animation.animateTo(target, easeOut(durationMillis))
        }
    }

    /** Reduce Motion cross-fade (iOS swaps move for opacity): ~0.2 s easeOut. */
    const val REDUCED_FADE_MS = 200

    /**
     * Compose scales every animation by the system animator scale and jumps to the end when it
     * is 0. Under "remove animations" iOS still cross-fades, so the reduced fade runs with this
     * scale in its coroutine context to keep its real 0.2 s duration.
     */
    val RealTime: MotionDurationScale = object : MotionDurationScale {
        override val scaleFactor: Float get() = 1f
    }

    // ---- C05 motion tokens (press feedback, value swaps, list entrance) ----

    /** Scale a pressed card / row settles at (SwiftUI plain button + a gentle shrink). */
    const val PRESS_SCALE_CARD = 0.97f
    /** Scale a pressed glyph button settles at. */
    const val PRESS_SCALE_GLYPH = 0.86f
    /** Opacity of a pressed control (UIKit highlighted plain button). */
    const val PRESS_ALPHA = 0.6f
    /** Press-in: quick easeOut, so the touch reads at once. */
    fun <T> pressIn(): FiniteAnimationSpec<T> = easeOut(90)
    /** Release: a lightly under-damped spring, so the control settles back with a small rebound. */
    fun <T> pressRelease(visibilityThreshold: T? = null) =
        spring(dampingRatio = 0.62f, stiffness = 700f, visibilityThreshold = visibilityThreshold)
    /** Selection marks (checks, stars) popping in. */
    fun <T> pop(visibilityThreshold: T? = null) =
        spring(dampingRatio = 0.55f, stiffness = 520f, visibilityThreshold = visibilityThreshold)
    /** Per-row delay of the material list's opening cascade, and the rows it covers. */
    const val LIST_STAGGER_MS = 22
    const val LIST_STAGGER_ROWS = 9
    /** How long after a list opens rows still cascade in (later rows appear by scrolling). */
    const val LIST_ENTRANCE_WINDOW_MS = 420L
}

/**
 * Reduce Motion for leaf controls that take no parameter for it. The app provides the live
 * system value; isolated previews default to full motion.
 */
internal val LocalSpReduceMotion = compositionLocalOf { false }

/**
 * Pressed-state feedback for a control driven by [interaction]: it dims to [SpMotion.PRESS_ALPHA]
 * and shrinks to [pressedScale], then springs back on release. Under Reduce Motion only the dim
 * remains (iOS keeps highlight, drops movement). Place it where the whole control should scale.
 */
internal fun Modifier.spPressFeedback(
    interaction: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = SpMotion.PRESS_SCALE_CARD,
): Modifier = composed {
    val reduce = LocalSpReduceMotion.current
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed && enabled
    val p by animateFloatAsState(
        if (down) 1f else 0f,
        if (down) SpMotion.pressIn() else SpMotion.pressRelease(0.001f),
        label = "spPress",
    )
    graphicsLayer {
        val k = p
        val s = if (reduce) 1f else 1f - (1f - pressedScale) * k
        scaleX = s; scaleY = s
        alpha = 1f - (1f - SpMotion.PRESS_ALPHA) * k.coerceIn(0f, 1f)
    }
}

/** A ripple-free tap target with press feedback on everything after it in the chain. */
internal fun Modifier.spPressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    pressedScale: Float = SpMotion.PRESS_SCALE_CARD,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    this.spPressFeedback(src, enabled, pressedScale)
        .clickable(interactionSource = src, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/**
 * Value-change swap for AnimatedContent: the new value rises in while the old one lifts away,
 * both fading (a picker label, a material name). Reduce Motion: a plain cross-fade.
 */
internal fun <S> spSwapTransition(
    reduce: Boolean,
    rise: Boolean = true,
): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    if (reduce) {
        fadeIn(SpMotion.easeOut(SpMotion.REDUCED_FADE_MS)) togetherWith fadeOut(SpMotion.easeOut(SpMotion.REDUCED_FADE_MS))
    } else {
        val dir = if (rise) 1 else -1
        (slideInVertically(SpMotion.smooth028(IntOffset.VisibilityThreshold)) { h -> dir * h / 2 } + fadeIn(SpMotion.easeOut(220))) togetherWith
            (slideOutVertically(SpMotion.smooth028(IntOffset.VisibilityThreshold)) { h -> -dir * h / 2 } + fadeOut(SpMotion.easeOut(140))) using
            SizeTransform(clip = false) { _, _ -> SpMotion.smooth028(IntSize.VisibilityThreshold) }
    }
}

/**
 * Card swap (box art when the film changes): the new card slides in from the trailing side with
 * a slight scale-up while the old one slides out and fades. Reduce Motion: a cross-fade.
 */
internal fun <S> spCardSwapTransition(reduce: Boolean): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    if (reduce) {
        fadeIn(SpMotion.easeOut(SpMotion.REDUCED_FADE_MS)) togetherWith fadeOut(SpMotion.easeOut(SpMotion.REDUCED_FADE_MS))
    } else {
        (slideInHorizontally(SpMotion.smooth028(IntOffset.VisibilityThreshold)) { w -> w / 3 } +
            scaleIn(SpMotion.smooth028(), initialScale = 0.9f) + fadeIn(SpMotion.easeOut(220))) togetherWith
            (slideOutHorizontally(SpMotion.smooth028(IntOffset.VisibilityThreshold)) { w -> -w / 3 } +
                scaleOut(SpMotion.smooth028(), targetScale = 0.94f) + fadeOut(SpMotion.easeOut(160))) using
            SizeTransform(clip = true)
    }
}

/**
 * List entrance: a row composed within [SpMotion.LIST_ENTRANCE_WINDOW_MS] of [openedAtMs] rises
 * 12dp and fades in, staggered by its [index]; rows that appear later (by scrolling) show at once.
 */
internal fun Modifier.spListEntrance(index: Int, openedAtMs: Long, reduce: Boolean): Modifier = composed {
    val animate = remember(openedAtMs) {
        !reduce && index < SpMotion.LIST_STAGGER_ROWS &&
            android.os.SystemClock.uptimeMillis() - openedAtMs in 0L until SpMotion.LIST_ENTRANCE_WINDOW_MS
    }
    val p = remember(openedAtMs) { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(reduce) {
        if (reduce) {
            p.snapTo(1f)
        } else {
            kotlinx.coroutines.delay(index.toLong() * SpMotion.LIST_STAGGER_MS)
            p.animateTo(1f, SpMotion.smooth030(0.001f))
        }
    }
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    graphicsLayer {
        alpha = if (reduce) 1f else p.value.coerceIn(0f, 1f)
        translationY = if (reduce) 0f else (1f - p.value) * rise
    }
}

/** Fade in late-loading artwork. The caller keys this composable to the artwork request. */
@Composable
internal fun SpArtworkReveal(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    val present = bitmap != null
    val reduce = LocalSpReduceMotion.current
    val alpha = remember { Animatable(if (present) 1f else 0f) }
    LaunchedEffect(present, reduce) {
        if (present) SpMotion.animateOpacity(alpha, 1f, reduce, 180) else alpha.snapTo(0f)
    }
    if (bitmap != null) {
        Image(bitmap, null, contentScale = ContentScale.Crop, modifier = modifier.graphicsLayer { this.alpha = alpha.value })
    }
}

/** True when the system animator duration scale is 0 ("Remove animations"), observed live. */
@Composable
internal fun rememberSpReduceMotion(): State<Boolean> {
    val ctx = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(spAnimationsOff(ctx)) }
    DisposableEffect(ctx) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state.value = spAnimationsOff(ctx) }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        runCatching { ctx.contentResolver.registerContentObserver(uri, false, observer) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(observer) } }
    }
    return state
}

internal fun spAnimationsOff(ctx: Context): Boolean =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

/**
 * ToolTickRuler haptics (UIImpactFeedbackGenerator .light): a major tick at intensity 1.0,
 * a minor tick at 0.6, release at 0.4. Android has no intensity on HapticFeedbackConstants,
 * so the levels map to the closest constants per API level:
 *  - API 34+: major SEGMENT_TICK, minor SEGMENT_FREQUENT_TICK;
 *  - API 30–33: major CLOCK_TICK + minor TEXT_HANDLE_MOVE (lighter);
 *  - below: CLOCK_TICK for both.
 * Release: KEYBOARD_RELEASE (API 27+, the lightest view haptic), else CLOCK_TICK.
 */
internal object SpHaptics {
    fun tick(view: View, major: Boolean) {
        val c = when {
            Build.VERSION.SDK_INT >= 34 -> if (major) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            Build.VERSION.SDK_INT >= 27 -> if (major) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.TEXT_HANDLE_MOVE
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(c)
    }

    fun release(view: View) {
        // No VIBRATE permission is declared (and none is added), so the 0.4 release uses the
        // lightest view haptic instead of a VibrationEffect composition.
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE else HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    /** A selection change (stage tab, material) — light. */
    fun select(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
        )
    }
}

/**
 * A selection mark (check, favourite star) that pops in with a small overshoot and shrinks away.
 * Reduce Motion: fades only.
 */
@Composable
internal fun SpPopIn(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = LocalSpReduceMotion.current
    androidx.compose.animation.AnimatedVisibility(
        visible, modifier,
        enter = if (reduce) fadeIn(SpMotion.easeOut(SpMotion.REDUCED_FADE_MS)) else scaleIn(SpMotion.pop(), initialScale = 0.4f) + fadeIn(SpMotion.easeOut(120)),
        exit = if (reduce) fadeOut(SpMotion.easeOut(SpMotion.REDUCED_FADE_MS)) else scaleOut(SpMotion.easeOut(140), targetScale = 0.6f) + fadeOut(SpMotion.easeOut(120)),
    ) { content() }
}
