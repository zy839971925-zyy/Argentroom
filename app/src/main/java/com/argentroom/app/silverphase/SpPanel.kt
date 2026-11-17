/*
 * Silverphase Android port — the floating process panel. GPLv3.
 * CompactPhoneLayout (CompactPhoneEditor.swift) + processPanel (SilverphaseApp.swift:772-850):
 * 12dp gutters, 56dp tabs at the bottom, the panel floats above them; max 520dp and at most
 * 72% of the work area (25%..72% drag range, 176dp floor); ChromeSurface radius 24; a 28x3
 * capsule grip in a 14dp strip; a 44dp header whose title area also drags the height; the
 * header's right side holds the ellipsis (重置本工艺) and 收起面板, or 完成 while the material
 * list is open; a 1dp rule under the header; the body scrolls.
 *
 * Android additions for one-handed use (recorded in UI_SCREEN_MAP.md): header buttons are
 * 48dp touch targets; dragging the grip/title down past the smallest height, or pulling the
 * body down when it is scrolled to the top, folds the panel back into its tab under the
 * finger, and the release hands its velocity to the closing/re-opening spring.
 */
package com.argentroom.app.silverphase

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/** CompactPhoneLayout geometry, in dp, for an editor work area of [w] x [h]. */
internal data class SpPhoneLayout(
    val tabsTop: Dp,
    val maxPanelHeight: Dp,
    val panelHeight: Dp,
    val panelBottom: Dp,
) {
    companion object {
        fun of(w: Dp, h: Dp, fraction: Float, showsPhotos: Boolean): SpPhoneLayout {
            val gap = SpDesign.gutter
            val tabsTop = maxOf(gap, h - SpDesign.tabsHeight - gap)
            val mediaH = if (showsPhotos) (if (h < 480.dp) 84.dp else SpDesign.browserHeight) else 0.dp
            val mediaTop = tabsTop - gap - mediaH
            val bottom = (if (showsPhotos) mediaTop else tabsTop) - gap
            val top = gap
            val maxH = maxOf(1.dp, minOf(SpDesign.panelMaxHeight, h * SpDesign.panelMaxFraction, bottom - top))
            val height = minOf(maxH, maxOf(minOf(176.dp, maxH), h * fraction.coerceIn(0.25f, 0.72f)))
            return SpPhoneLayout(tabsTop, maxH, height, bottom)
        }
    }
}

/**
 * Turns vertical drags into (a) height changes and (b) a fold-away once the height is at its
 * floor. [resize] takes a dy in px (positive = down) and returns the part it could not use.
 * [dismissDistancePx] is how far a drag must travel to fold the panel completely.
 */
internal class SpPanelDrag(
    private val morph: SpMorphState,
    private val resize: (Float) -> Float,
    private val dismissDistancePx: () -> Float,
    private val onDismissed: () -> Unit,
) {
    private val tracker = VelocityTracker()
    private var total = 0f
    private var folding = false

    fun start() { tracker.resetTracking(); total = 0f; folding = false }

    fun drag(dy: Float, timeMs: Long) {
        total += dy
        tracker.addPosition(timeMs, Offset(0f, total))
        apply(dy)
    }

    /** Returns the part of [dy] consumed. */
    fun apply(dy: Float): Float {
        val d = dismissDistancePx().coerceAtLeast(1f)
        if (dy > 0f) {
            val left = if (folding) dy else resize(dy)
            if (left > 0f) {
                folding = true
                morph.track(morph.gestureProgress - left / d)
            }
            return dy
        }
        // Up: first undo a fold in progress, then grow.
        if (folding) {
            val p = morph.gestureProgress
            val back = minOf(-dy / d, 1f - p)
            morph.track(p + back)
            if (p + back >= 1f) folding = false
            val usedPx = back * d
            val rest = dy + usedPx
            if (rest < 0f) resize(rest)
            return dy
        }
        resize(dy)
        return dy
    }

    val isFolding: Boolean get() = folding

    fun end(velocityY: Float? = null) {
        val vy = velocityY ?: tracker.calculateVelocity().y
        if (folding) {
            val d = dismissDistancePx().coerceAtLeast(1f)
            morph.release(-vy / d, onDismissed)
        }
        folding = false
    }

    /** Body scroll at its top: pulling further down folds the panel. */
    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (folding && available.y < 0f && source == NestedScrollSource.UserInput) return Offset(0f, apply(available.y))
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0f && source == NestedScrollSource.UserInput) {
                if (!folding) folding = true
                val d = dismissDistancePx().coerceAtLeast(1f)
                morph.track(morph.gestureProgress - available.y / d)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (folding) { end(available.y); return available }
            return Velocity.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (folding) end(available.y)
            return Velocity.Zero
        }
    }
}

/** The panel's content inside its morph surface: grip, header, rule, body. */
@Composable
internal fun SpFloatingPanel(
    title: String,
    drag: SpPanelDrag,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    val dragMod = Modifier.pointerInput(drag) {
        detectVerticalDragGestures(
            onDragStart = { drag.start() },
            onDragEnd = { drag.end() },
            onDragCancel = { drag.end() },
        ) { ch, dy -> ch.consume(); drag.drag(dy, ch.uptimeMillis) }
    }
    Column(modifier.fillMaxSize().nestedScroll(drag.nestedScroll)) {
        // 14dp strip with the 28x3 capsule (the strip's touch height is padded to 20dp).
        Box(Modifier.fillMaxWidth().height(SpDesign.panelGrip + 6.dp).then(dragMod), contentAlignment = Alignment.Center) {
            Box(Modifier.width(28.dp).height(3.dp).background(SpStyle.primary.copy(alpha = 0.25f), RoundedCornerShape(1.5.dp)))
        }
        Row(
            Modifier.fillMaxWidth().height(SpDesign.hitSize).padding(end = SpDesign.unit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).fillMaxSize().then(dragMod).padding(start = SpDesign.contentInset), contentAlignment = Alignment.CenterStart) {
                Crossfade(title, animationSpec = SpMotion.easeInOut(180), label = "panelTitle") { t ->
                    Text(t, style = SpDesign.panelTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                }
            }
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SpStyle.rule))
        Column(Modifier.fillMaxWidth().weight(1f), content = body)
    }
}

/** Header text button such as 完成 (48dp touch target). */
@Composable
internal fun SpHeaderTextButton(title: String, onClick: () -> Unit) {
    Box(
        Modifier.height(SpDesign.hitSize).padding(end = 4.dp).spPressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = SpDesign.parameter.copy(color = SpStyle.accent), modifier = Modifier.padding(horizontal = 12.dp)) }
}

/** Size of the panel's fixed chrome above the body. */
internal val SpPanelChromeHeight: Dp = SpDesign.panelGrip + 6.dp + SpDesign.hitSize + 1.dp
