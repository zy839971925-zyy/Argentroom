/*
 * Silverphase Android port — the seven process stages and their bottom control. GPLv3.
 * MobileStage (SilverphaseApp.swift) and CompactProcessTabs / ProcessSegmentsHost
 * (CompactPhoneEditor.swift).
 *
 * iOS hosts custom labels inside a native toolbar UISegmentedControl whose glass lens UIKit
 * draws and tracks. Android has no such control, so this is the visual/gesture equivalent the
 * handoff calls for: seven equal segments in a 56dp glass capsule, an orange label on the
 * selected one, a lens that follows the finger across segments, select on lift, and a tap on
 * the already-selected segment re-sends it (the iOS repeatedTap path).
 */
package com.argentroom.app.silverphase

import com.argentroom.app.*
import com.argentroom.app.editor.*
import com.argentroom.app.settings.*
import com.argentroom.app.ui.*
import com.argentroom.app.R

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** MobileStage, with the iOS raw ids kept for persistence. */
internal enum class SpStage(
    val id: String,
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val shortRes: Int,
    val glyph: SpGlyph,
) {
    CAMERA("camera", R.string.sp_stage_camera, R.string.sp_stage_camera, SpGlyph.APERTURE),
    FILM("film", R.string.sp_stage_film, R.string.sp_stage_film, SpGlyph.FILM),
    DEVELOPMENT("development", R.string.sp_stage_development, R.string.sp_stage_development, SpGlyph.DROP),
    PRINT("print", R.string.sp_stage_print, R.string.sp_stage_print_short, SpGlyph.PRINT),
    OUTPUT("output", R.string.sp_stage_output, R.string.sp_stage_output, SpGlyph.SCANNER),
    GEOMETRY("geometry", R.string.sp_stage_geometry, R.string.sp_stage_geometry, SpGlyph.ASPECT),
    FILM_EDGE("filmEdge", R.string.sp_stage_film_edge, R.string.sp_stage_film_edge, SpGlyph.DASHED);

    val title: String get() = spS(titleRes)
}

@Composable
internal fun SpProcessTabs(
    stage: SpStage?,
    enabled: Boolean,
    reduceMotion: Boolean,
    onSelect: (SpStage) -> Unit,
    modifier: Modifier = Modifier,
    /** Root bounds of each segment, for the panel morph's origin. */
    onSegmentOrigin: (SpStage, SpOrigin) -> Unit = { _, _ -> },
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val stages = SpStage.entries
    val currentSelect by rememberUpdatedState(onSelect)
    val currentStage by rememberUpdatedState(stage)
    // The segment under the finger while tracking (the moving lens), else null.
    var tracking by remember { mutableStateOf<Int?>(null) }
    val lensIndex = tracking ?: stage?.ordinal
    // Keep the last lens position while closing. A new press positions it under the finger.
    var lastLens by remember { mutableStateOf(stage?.ordinal ?: 0) }
    LaunchedEffect(lensIndex) {
        if (lensIndex != null) lastLens = lensIndex
    }
    val lensPos = animateFloatAsState(
        (lensIndex ?: lastLens).toFloat(),
        // UIKit's lens follows the finger with no visible bounce; .smooth(0.28) is the closest curve.
        if (reduceMotion) SpMotion.snap() else SpMotion.smooth028(0.001f),
        label = "lens",
    )
    val lensScale = animateFloatAsState(
        // C05: with no stage open the lens shrinks away into its segment instead of vanishing
        // in one frame (it used to flash and pop when the panel collapsed).
        when { tracking != null -> 1.06f; lensIndex == null -> 0.72f; else -> 1f },
        if (reduceMotion) SpMotion.snap() else SpMotion.smooth028(0.001f), label = "lensScale",
    )
    val lensVisible = lensIndex != null
    val lensAlpha = remember { Animatable(if (lensVisible) 1f else 0f) }
    LaunchedEffect(lensVisible, reduceMotion) {
        SpMotion.animateOpacity(lensAlpha, if (lensVisible) 1f else 0f, reduceMotion, 180)
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth().height(SpDesign.tabsHeight)
            .spChromeSurface(radius = 28.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val seg = size.width / stages.size.toFloat()
                fun indexAt(x: Float) = (x / seg).toInt().coerceIn(0, stages.lastIndex)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var idx = indexAt(down.position.x)
                    tracking = idx
                    var cancelled = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (ch.positionChange().getDistanceSquared() > 0f) ch.consume()
                        // Leaving the control vertically by a wide margin cancels, as UIKit does.
                        if (ch.position.y < -size.height * 1.5f || ch.position.y > size.height * 2.5f) { cancelled = true }
                        idx = indexAt(ch.position.x)
                        tracking = if (cancelled) null else idx
                    }
                    tracking = null
                    if (!cancelled) { SpHaptics.select(view); currentSelect(stages[idx]) }
                }
            },
    ) {
        val segW = maxWidth / stages.size
        if (lensAlpha.value > 0.001f) {
            // Glass selection lens behind the chosen segment; fades and shrinks out when closed.
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(((segW * lensPos.value) + 3.dp).roundToPx(), 4.dp.roundToPx()) }
                    .width(segW - 6.dp).height(SpDesign.tabsHeight - 8.dp)
                    .graphicsLayer { scaleX = lensScale.value; scaleY = lensScale.value; alpha = lensAlpha.value }
                    .background(SpStyle.primary.copy(alpha = if (tracking != null) 0.16f else 0.11f), RoundedCornerShape(24.dp)),
            )
        }
        Row(Modifier.fillMaxSize()) {
            for (s in stages) {
                val selected = s == stage
                // C05: colour cross-fades and the glyph gives a small spring pop on selection,
                // so opening/closing a stage never snaps.
                val color by androidx.compose.animation.animateColorAsState(
                    if (selected) SpStyle.accent else SpStyle.primary,
                    SpMotion.easeOut(180), label = "tabColor",
                )
                val pop by animateFloatAsState(
                    if (selected && !reduceMotion) 1.12f else 1f,
                    SpMotion.pop(0.001f),
                    label = "tabPop",
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .spOriginOf({ onSegmentOrigin(s, it) }, radiusPx = 24f * view.resources.displayMetrics.density)
                        .semantics {
                            contentDescription = s.title; this.selected = selected; role = Role.Tab
                            // The finger path is a raw pointerInput; TalkBack / Switch Access need an
                            // explicit action to open the stage.
                            if (enabled) onClick { SpHaptics.select(view); currentSelect(s); true } else disabled()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 36x44 label canvas centred in 56dp: glyph at y=5, title baseline box at y=29.
                    Box(
                        Modifier.padding(top = 6.dp + 5.dp).size(20.dp).graphicsLayer { scaleX = pop; scaleY = pop },
                        contentAlignment = Alignment.Center,
                    ) {
                        SpGlyphIcon(s.glyph, color, Modifier.size(20.dp))
                    }
                    Text(
                        spS(s.shortRes),
                        style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = color, textAlign = TextAlign.Center),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
