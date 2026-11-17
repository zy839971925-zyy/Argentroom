/*
 * Silverphase Android port — ToolTickRuler and RulerParameterRow. GPLv3.
 * Ported from Silverphase v1.2.5 ToolTickRuler.swift / ToolRulerScale.swift /
 * RulerParameterRow.swift: moving ticks under a fixed orange pointer, 12dp tick spacing,
 * 40 intervals per range, no velocity projection on release, a trailing highlight on the
 * ticks the pointer just crossed, haptics per tick, double-tap restores the true default,
 * tap the number to type a value. Horizontal drags only; a vertical drag goes to the panel's
 * scroll (Compose's horizontal drag detector fails on vertical slop, like the iOS
 * gestureRecognizerShouldBegin direction check).
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.R
import com.spectrafilm.app.*
import com.spectrafilm.app.library.*
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** ToolRulerScale.swift. */
internal object SpRulerScale {
    const val SPACING_DP = 12f

    fun step(lo: Double, hi: Double): Double {
        val raw = maxOf(0.0001, (hi - lo) / 40.0)
        val scale = 10.0.pow(floor(log10(raw)))
        return (listOf(1.0, 2.0, 2.5, 5.0, 10.0).firstOrNull { it * scale >= raw - 1e-10 } ?: 10.0) * scale
    }

    fun quantized(v: Double, lo: Double, hi: Double, resolution: Double): Double {
        val bounded = v.coerceIn(lo, hi)
        val rounded = (bounded / resolution).roundToLong() * resolution
        return rounded.coerceIn(lo, hi)
    }

    private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()

    fun majorStep(minor: Double): Double {
        if (abs(minor - 1.0 / 3) < 1e-8) return 1.0
        val raw = minor * 4
        val scale = 10.0.pow(floor(log10(raw)))
        return (listOf(1.0, 2.0, 5.0, 10.0).firstOrNull { it * scale >= raw - 1e-10 } ?: 10.0) * scale
    }

    data class Tick(val value: Double, val major: Boolean)

    fun ticks(visLo: Double, visHi: Double, lo: Double, hi: Double, step: Double, anchor: Double): List<Tick> {
        val marks = listOf(lo, hi, anchor).filter { it in lo..hi }.distinct()
        val first = ceil(maxOf(lo, visLo) / step - 1e-9).toInt()
        val last = floor(minOf(hi, visHi) / step + 1e-9).toInt()
        val major = majorStep(step)
        val out = ArrayList<Tick>()
        if (first <= last) for (i in first..last) {
            val v = i * step
            // Replace a nearby minor line with the exact default marker.
            if (marks.any { abs(it - v) < step * 0.45 }) continue
            out += Tick(v, abs(v / major - kotlin.math.round(v / major)) < 1e-7)
        }
        out += marks.filter { it in visLo..visHi }.map { Tick(it, true) }
        return out.sortedBy { it.value }
    }
}

/**
 * The ruler itself. [value] is the committed value; while dragging the ruler shows its own
 * interaction value and reports it through [onPreview] (RulerParameterRow commits it live,
 * which is what the iOS row does when it has an interaction handler).
 */
@Composable
internal fun SpTickRuler(
    value: Double,
    lo: Double,
    hi: Double,
    anchor: Double,
    resolution: Double,
    step: Double?,
    enabled: Boolean,
    onPreview: (Double) -> Unit,
    onDragging: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val spacingPx = with(density) { SpRulerScale.SPACING_DP.dp.toPx() }
    val unitsPerTick = step ?: SpRulerScale.step(lo, hi)
    var interaction by remember { mutableStateOf<Double?>(null) }
    var overscroll by remember { mutableFloatStateOf(0f) }
    var highlightDir by remember { mutableFloatStateOf(0f) }
    val highlight = remember { Animatable(0f) }
    val settle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // settle.value is read only in the draw lambda below (no recomposition per settle frame).
    val shownBase = (interaction ?: value) + overscroll
    val currentValue by rememberUpdatedState(value)
    val currentPreview by rememberUpdatedState(onPreview)
    val currentDragging by rememberUpdatedState(onDragging)
    val currentReset by rememberUpdatedState(onReset)
    var dragStart by remember { mutableStateOf<Double?>(null) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    var lastStep by remember { mutableStateOf<Int?>(null) }

    fun endInteraction() {
        val wasDragging = dragStart != null
        dragStart = null; interaction = null; overscroll = 0f; lastStep = null; accumulated = 0f
        if (wasDragging) currentDragging(false)
    }
    // A control that disappears mid-drag must end the continuous edit (iOS onDisappear).
    DisposableEffect(Unit) { onDispose { if (dragStart != null) currentDragging(false) } }

    Canvas(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(enabled, lo, hi, anchor, unitsPerTick) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStart = currentValue
                        accumulated = 0f
                        lastStep = (currentValue / unitsPerTick).roundToInt()
                        currentDragging(true)
                        scope.launch { settle.snapTo(0f) }
                    },
                    onDragEnd = {
                        val target = SpRulerScale.quantized(interaction ?: currentValue, lo, hi, resolution)
                        val from = (interaction ?: currentValue) + overscroll - target
                        dragStart = null; interaction = null; overscroll = 0f; lastStep = null
                        currentPreview(target)
                        currentDragging(false)
                        SpHaptics.release(view)
                        // Precision release: no velocity projection, no overshoot (ToolTickRuler.swift:1-3).
                        scope.launch {
                            settle.snapTo(from.toFloat())
                            settle.animateTo(0f, SpMotion.easeOut(140))
                            highlight.animateTo(0f, SpMotion.easeOut(180))
                        }
                    },
                    onDragCancel = { endInteraction(); scope.launch { highlight.snapTo(0f) } },
                ) { change, dx ->
                    change.consume()
                    val start = dragStart ?: return@detectHorizontalDragGestures
                    accumulated += dx
                    val proposed = start - accumulated / spacingPx * unitsPerTick
                    val clamped = SpRulerScale.quantized(proposed, lo, hi, resolution)
                    val prev = interaction ?: currentValue
                    if (abs(clamped - prev) > 0.0001) {
                        highlightDir = if (clamped > prev) 1f else -1f
                        scope.launch { highlight.snapTo(1f) }
                    }
                    interaction = clamped
                    currentPreview(clamped)
                    val excess = proposed - clamped
                    overscroll = (excess / (1 + abs(excess) / (unitsPerTick * 2)) * 0.2).toFloat()
                    val s = (clamped / unitsPerTick).roundToInt()
                    if (s != lastStep) {
                        val tick = kotlin.math.round(clamped / unitsPerTick) * unitsPerTick
                        val majorUnit = SpRulerScale.majorStep(unitsPerTick)
                        val major = abs(clamped - anchor) < resolution / 2 ||
                            abs(tick / majorUnit - kotlin.math.round(tick / majorUnit)) < 1e-7
                        SpHaptics.tick(view, major)
                        lastStep = s
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onDoubleTap = {
                    endInteraction()
                    currentReset()
                    SpHaptics.release(view)
                })
            },
    ) {
        val shown = shownBase + settle.value
        val length = size.width
        val center = length / 2
        val half = (length / (2 * spacingPx) + 1) * unitsPerTick
        val ticks = SpRulerScale.ticks(shown - half, shown + half, lo, hi, unitsPerTick, anchor)
        val baseAlpha = if (enabled) 1f else 0.35f
        for (t in ticks) {
            val x = (center + (t.value - shown) / unitsPerTick * spacingPx).toFloat()
            val past = (center - x) * highlightDir
            val prox = if (past >= 0) maxOf(0f, 1f - past / (spacingPx * 1.25f)) else 0f
            val hl = prox * prox * (3 - 2 * prox) * highlight.value
            val baseH = (if (t.major) 20.dp else 12.dp).toPx()
            val baseW = (if (t.major) 1.8.dp else 1.2.dp).toPx()
            val h = baseH + (22.dp.toPx() - baseH) * hl
            val w = baseW + (3.dp.toPx() - baseW) * hl
            val tl = Offset(x - w / 2, size.height - h)
            val tickColor = SpStyle.primary.copy(alpha = (if (t.major) 0.6f else 0.3f) * (1 - hl) * baseAlpha)
            drawRoundRect(tickColor, tl, Size(w, h), CornerRadius(w / 2))
            if (hl > 0f) drawRoundRect(SpStyle.accent.copy(alpha = hl), tl, Size(w, h), CornerRadius(w / 2))
        }
        // Edge fade: 0 -> 18% -> 82% -> 100%, as the iOS LinearGradient mask.
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent, 0.18f to SpStyle.ink, 0.82f to SpStyle.ink, 1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
        // Fixed pointer: 3x22 capsule at the bottom centre, with a soft accent glow.
        val pw = 3.dp.toPx(); val ph = 22.dp.toPx()
        drawRoundRect(
            SpStyle.accent.copy(alpha = 0.3f * baseAlpha),
            Offset(center - pw / 2 - 2.dp.toPx(), size.height - ph - 2.dp.toPx()),
            Size(pw + 4.dp.toPx(), ph + 2.dp.toPx()), CornerRadius(pw),
        )
        drawRoundRect(
            SpStyle.accent.copy(alpha = baseAlpha), Offset(center - pw / 2, size.height - ph),
            Size(pw, ph), CornerRadius(pw / 2),
        )
    }
}

internal fun formatFixed(v: Double, digits: Int): String = String.format(java.util.Locale.US, "%.${digits}f", v)

/** RulerParameterRow.swift: title + tappable number, then the 44dp ruler, 8dp after. */
@Composable
internal fun SpRulerRow(
    title: String,
    value: Double,
    lo: Double,
    hi: Double,
    defaultValue: Double,
    onValue: (Double) -> Unit,
    onReset: () -> Unit,
    onInteraction: (Boolean) -> Unit,
    fractionDigits: Int = 2,
    entryLo: Double = lo,
    entryHi: Double = hi,
    step: Double? = null,
    enabled: Boolean = true,
    @androidx.annotation.StringRes help: Int? = null,
) {
    var helpOpen by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Double?>(null) }
    var entering by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf("") }
    val resolution = 10.0.pow(-fractionDigits)
    val shown = preview ?: value
    // The ruler's range always contains the default (ProcessControls.number).
    val rLo = minOf(lo, defaultValue); val rHi = maxOf(hi, defaultValue)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
    ) {
        // C02: 44dp rows as RulerParameterRow.swift (44 + 44 + 8); the value box keeps a 48dp-wide
        // target and the ruler strip takes the whole row width, so nothing falls under 44dp.
        Row(Modifier.fillMaxWidth().heightIn(min = SpDesign.iosHitSize), verticalAlignment = Alignment.CenterVertically) {
            SpHelpLabel(title, help, helpOpen, { helpOpen = !helpOpen }, Modifier.weight(1f))
            Box(
                Modifier.widthIn(min = SpDesign.hitSize).height(SpDesign.iosHitSize)
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onInteraction(false)
                        entry = formatFixed(value, fractionDigits); entering = true
                    }
                    .semantics { contentDescription = spS(com.spectrafilm.app.R.string.sp_enter_value, title) },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(formatFixed(shown, fractionDigits), style = SpDesign.numericValue, textAlign = TextAlign.End)
            }
        }
        SpTickRuler(
            value = value, lo = rLo, hi = rHi, anchor = defaultValue, resolution = resolution, step = step,
            enabled = enabled,
            onPreview = { preview = it; onValue(it) },
            onDragging = { dragging -> if (!dragging) preview = null; onInteraction(dragging) },
            onReset = { preview = null; onReset() },
            // The whole 48dp strip takes the horizontal drag: no need to hit a tick.
            modifier = Modifier.fillMaxWidth().height(SpDesign.iosHitSize)
                .semantics {
                    contentDescription = title
                    stateDescription = formatFixed(shown, fractionDigits)
                    // steps = tick count, so TalkBack's adjust gesture moves one ruler tick at a time.
                    val tick = step ?: SpRulerScale.step(rLo, rHi)
                    val steps = (((rHi - rLo) / tick).roundToInt() - 1).coerceIn(0, 1000)
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), rLo.toFloat()..rHi.toFloat(), steps)
                    if (enabled) {
                        setProgress { target ->
                            onInteraction(true)
                            onValue(SpRulerScale.quantized(target.toDouble(), rLo, rHi, resolution))
                            onInteraction(false)
                            true
                        }
                    } else disabled()
                },
        )
        SpRulerHelp(helpOpen, help)
    }
    if (entering) {
        AlertDialog(
            onDismissRequest = { entering = false },
            title = { Text(spS(com.spectrafilm.app.R.string.sp_enter_value, title)) },
            text = {
                Column {
                    OutlinedTextField(
                        entry, { entry = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Text(
                        spS(com.spectrafilm.app.R.string.sp_range, trimNumber(entryLo), trimNumber(entryHi)), style = SpDesign.help,
                        color = SpStyle.secondaryText, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton({
                    entering = false
                    onInteraction(false)
                    entry.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
                        onValue(it.coerceIn(entryLo, entryHi))
                    }
                }) { Text(spS(com.spectrafilm.app.R.string.sp_ok)) }
            },
            dismissButton = { TextButton({ entering = false }) { Text(spS(com.spectrafilm.app.R.string.sp_cancel)) } },
        )
    }
}

internal fun trimNumber(v: Double): String =
    if (v == kotlin.math.floor(v) && abs(v) < 1e9) v.toLong().toString() else v.toString()


@Composable
private fun SpRulerHelp(open: Boolean, @androidx.annotation.StringRes help: Int?) {
    if (help == null) return
    androidx.compose.animation.AnimatedVisibility(
        open,
        enter = androidx.compose.animation.expandVertically(SpMotion.smooth028(androidx.compose.ui.unit.IntSize.VisibilityThreshold)) +
            androidx.compose.animation.fadeIn(SpMotion.easeOut(180)),
        exit = androidx.compose.animation.fadeOut(SpMotion.easeOut(140)) +
            androidx.compose.animation.shrinkVertically(SpMotion.smooth028(androidx.compose.ui.unit.IntSize.VisibilityThreshold)),
    ) {
        Text(spS(help), style = SpDesign.help, color = SpStyle.secondaryText, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
