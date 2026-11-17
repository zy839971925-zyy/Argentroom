/*
 * Argentroom camera — viewfinder overlays (grid, level, focus square), notices and finder gestures. GPLv3.
 * Film modeling powered by spektrafilm.
 * Split out of SpCameraScreen.kt; see that file for the screen's design notes.
 */
package com.argentroom.app.camera

import com.argentroom.app.*
import com.argentroom.app.diagnostics.*
import com.argentroom.app.editor.*
import com.argentroom.app.export.*
import com.argentroom.app.settings.*
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.argentroom.app.R
import com.argentroom.app.silverphase.SpDesign
import com.argentroom.app.silverphase.SpHaptics
import com.argentroom.app.silverphase.SpMotion
import com.argentroom.app.silverphase.SpStyle
import com.argentroom.app.silverphase.spChromeSurface
import com.argentroom.app.silverphase.spClickable
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

@Composable
internal fun GridOverlay(mode: GridMode) {
    if (mode == GridMode.OFF) return
    Canvas(Modifier.fillMaxSize()) {
        // MobileCameraView.compositionGuides: thirds, or the golden section (3 - sqrt 5) / 2.
        val d = if (mode == GridMode.GOLDEN) ((3.0 - sqrt(5.0)) / 2.0).toFloat() else 1f / 3f
        for (pass in 0..1) {
            val color = if (pass == 0) SpStyle.ink.copy(alpha = 0.28f) else SpStyle.primary.copy(alpha = 0.35f)
            val w = if (pass == 0) 2.dp.toPx() else 1.dp.toPx()
            for (f in floatArrayOf(d, 1f - d)) {
                drawLine(color, Offset(size.width * f, 0f), Offset(size.width * f, size.height), strokeWidth = w)
                drawLine(color, Offset(0f, size.height * f), Offset(size.width, size.height * f), strokeWidth = w)
            }
        }
    }
}

@Composable
internal fun BoxScope.LevelOverlay(rotation: Float) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var roll by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(ctx) {
        val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                // Too flat (facing up/down) to read a horizon: hide, as iOS does under 0.2 g.
                roll = if (hypot(x, y) > 0.2f * SensorManager.GRAVITY_EARTH) {
                    val r = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat()
                    kotlin.math.round(r * 2f) / 2f
                } else {
                    null
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sensors?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors?.unregisterListener(listener) }
    }
    val r = roll ?: return
    val levelAngle = -Math.IEEEremainder(r.toDouble(), 90.0).toFloat()
    val aligned = abs(levelAngle) < 1.5f
    var wasAligned by remember { mutableStateOf(false) }
    LaunchedEffect(aligned) {
        if (aligned && !wasAligned) SpHaptics.select(view)
        wasAligned = aligned
    }
    Row(
        Modifier.align(Alignment.Center).rotate(rotation),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(18.dp, 2.dp).clip(CircleShape).background(SpStyle.primary.copy(alpha = 0.6f)))
        Box(
            Modifier
                .size(38.dp, 2.dp)
                .rotate(if (aligned) 0f else levelAngle)
                .clip(CircleShape)
                .background(if (aligned) SpStyle.accent else SpStyle.primary),
        )
        Box(Modifier.size(18.dp, 2.dp).clip(CircleShape).background(SpStyle.primary.copy(alpha = 0.6f)))
    }
}

@Composable
internal fun BoxScope.FocusOverlay(
    point: Offset?,
    locked: Boolean,
    tick: Int,
    dragging: Boolean,
    ev: Float,
    rotation: Float,
    reduceMotion: Boolean,
) {
    val p = point ?: return
    val density = LocalDensity.current
    val pulse = remember { Animatable(1f) }
    var dim by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        dim = false
        if (!reduceMotion) {
            pulse.snapTo(1.28f)
            pulse.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 600f))
        }
        if (!locked) {
            delay(2600)
            dim = true
        }
    }
    val alpha by animateFloatAsState(if (dim && !dragging) 0.45f else 1f, SpMotion.easeOut(200), label = "focusAlpha")
    val boxPx = with(density) { 56.dp.toPx() }
    // Centred on the tap (MobileCameraView.focusIndicator; its edge insets are not ported).
    Box(
        Modifier
            .offset { IntOffset((p.x - boxPx / 2f).toInt(), (p.y - boxPx / 2f).toInt()) }
            .size(56.dp)
            .graphicsLayer { scaleX = pulse.value; scaleY = pulse.value; this.alpha = alpha }
            .border(1.5.dp, SpStyle.accent, RoundedCornerShape(5.dp)),
    ) {
        if (!locked) {
            // The sun handle rides the exposure (MobileCameraView: offset = -ev / travel * 22).
            val travel = if (ev >= 0f) SpCameraDevelop.EV_MAX else -SpCameraDevelop.EV_MIN
            val offsetDp = (-(ev / travel) * 22f).dp
            SpCamGlyphIcon(
                SpCamGlyph.SUN, SpStyle.accent,
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset { with(density) { IntOffset(18.dp.roundToPx(), offsetDp.roundToPx()) } }
                    .size(12.dp),
            )
        }
    }
    val badge = when {
        locked -> stringResource(R.string.spcam_ae_af_lock)
        dragging -> stringResource(R.string.spcam_ev_badge, formatEv(ev))
        else -> null
    }
    if (badge != null) {
        Row(
            Modifier
                .offset { IntOffset((p.x - boxPx / 2f).toInt(), (p.y - boxPx / 2f - with(density) { 30.dp.toPx() }).toInt()) }
                .rotate(rotation)
                .clip(CircleShape)
                .background(SpStyle.ink.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locked) SpCamGlyphIcon(SpCamGlyph.LOCK, SpStyle.accent, Modifier.size(10.dp).padding(end = 1.dp))
            Text(
                badge, style = SpDesign.badge, color = SpStyle.accent, maxLines = 1,
                modifier = Modifier.padding(start = if (locked) 3.dp else 0.dp),
            )
        }
    }
}

@Composable
internal fun SpCameraNotice(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .spChromeSurface(radius = 20.dp)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpCamGlyphIcon(SpCamGlyph.FLIP, SpStyle.secondaryText, Modifier.size(28.dp))
            Text(title, style = SpDesign.panelTitle, textAlign = TextAlign.Center)
            Text(body, style = SpDesign.panelSummary, color = SpStyle.secondaryText, textAlign = TextAlign.Center)
            if (action != null) {
                Text(
                    action,
                    style = SpDesign.selectedValue.copy(fontSize = 14.sp),
                    color = SpStyle.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(CircleShape)
                        .background(SpStyle.accent)
                        .spClickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Gestures: tap = focus, long press = AE/AF lock, vertical drag = film exposure, pinch = zoom
// ---------------------------------------------------------------------------------------------

internal fun Modifier.finderGestures(
    enabled: () -> Boolean,
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onZoom: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled()) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        val downAt = SystemClock.uptimeMillis()
        // 0 undecided, 1 exposure drag, 2 zoom, 3 ignored pan, 4 long-pressed
        var mode = 0
        var total = Offset.Zero
        var lastSpan = 0f
        while (true) {
            val remaining = longPressMs - (SystemClock.uptimeMillis() - downAt)
            val event = if (mode == 0 && remaining > 0) {
                withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else if (mode == 0) {
                null
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                mode = 4
                onLongPress(down.position)
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                when (mode) {
                    0 -> onTap(down.position)
                    1 -> onDragEnd()
                }
                break
            }
            if (pressed.size >= 2) {
                if (mode == 1) onDragEnd()
                mode = 2
                val a = pressed[0].position
                val b = pressed[1].position
                val span = (a - b).getDistance()
                if (lastSpan > 0f && span > 0f) onZoom(span / lastSpan)
                lastSpan = span
                event.changes.forEach { it.consume() }
                continue
            }
            if (mode == 2) {
                lastSpan = 0f
                continue
            }
            val change = pressed.first()
            val delta = change.positionChange()
            total += delta
            if (mode == 0 && total.getDistance() > slop) {
                mode = if (abs(total.y) > abs(total.x)) 1 else 3
            }
            if (mode == 1) {
                onDrag(delta.y)
                change.consume()
            }
        }
    }
}
