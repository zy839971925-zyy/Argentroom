/*
 * Argentroom — MobileLatitudeGraph.swift on Compose. GPLv3.
 * 108dp canvas: −8…+8 EV axis, the scene's distribution (square-root height, grey inside the
 * medium's boundaries and orange outside), dashed boundaries with their EV, the print's tonal
 * separation band, then the below / within / above shares and the stops held.
 */
package com.argentroom.app.silverphase

import com.argentroom.app.*
import com.argentroom.app.library.*
import com.argentroom.app.look.*
import com.argentroom.app.render.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.argentroom.app.R
import kotlin.math.sqrt

/** What the 拍摄 panel shows for the latitude measurement. */
internal sealed interface SpLatitudeUi {
    data object Idle : SpLatitudeUi
    data object Measuring : SpLatitudeUi
    data class Ready(val latitude: SpLatitude, val refreshing: Boolean) : SpLatitudeUi
    data class Failed(val message: String) : SpLatitudeUi
}

@Composable
internal fun SpLatitudeGraph(latitude: SpLatitude, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val label = SpDesign.instrument.copy(color = SpStyle.secondary)
    val a11y = spS(R.string.sp_latitude_a11y)
    Column(modifier.fillMaxWidth().semantics { contentDescription = a11y }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(108.dp).clipToBounds()) {
            val w = size.width
            val plotH = 78.dp.toPx()
            val bandY = 82.dp.toPx()
            fun x(ev: Double): Float = ((ev + 8) / 16 * w).toFloat()
            val centres = latitude.centres
            val bins = latitude.bins.indices.filter { centres[it] in -8.0..8.0 }.map { centres[it] to latitude.bins[it] }
            val peak = maxOf(bins.maxOfOrNull { sqrt(maxOf(0.0, it.second)) } ?: 0.0, 1e-9)
            val half = (latitude.hiEV - latitude.loEV) / maxOf(1, latitude.bins.size) / 2
            drawRect(SpStyle.ink.copy(alpha = 0.1f), size = Size(w, plotH))
            for (ev in -6..6 step 2) {
                drawLine(
                    SpStyle.primary.copy(alpha = if (ev == 0) 0.18f else 0.07f),
                    Offset(x(ev.toDouble()), 0f), Offset(x(ev.toDouble()), plotH), strokeWidth = 0.5.dp.toPx(),
                )
            }
            val hist = Path().apply {
                moveTo(0f, plotH)
                for ((ev, f) in bins) {
                    val y = (plotH - (plotH - 4.dp.toPx()) * sqrt(maxOf(0.0, f)) / peak).toFloat()
                    lineTo(x(ev - half), y); lineTo(x(ev + half), y)
                }
                lineTo(w, plotH); close()
            }
            val low = x(latitude.shadow).coerceIn(0f, w)
            val high = x(latitude.highlight).coerceIn(low, w)
            for ((start, end, inside) in listOf(Triple(0f, low, false), Triple(low, high, true), Triple(high, w, false))) {
                if (end <= start) continue
                clipRect(start, 0f, end, plotH) {
                    drawPath(hist, SpStyle.primary.copy(alpha = if (inside) 0.5f else 0.18f))
                    drawPath(hist, if (inside) SpStyle.primary.copy(alpha = 0.7f) else SpStyle.accent, style = Stroke(1.dp.toPx()))
                }
            }
            val sep = latitude.separation.filter { it.first in -8.0..8.0 }
            if (sep.size > 1) {
                val stops = sep.map { (((it.first + 8) / 16).toFloat()) to SpStyle.primary.copy(alpha = (it.second * 0.85).toFloat()) }.toTypedArray()
                drawRoundRect(
                    Brush.horizontalGradient(*stops, startX = 0f, endX = w),
                    topLeft = Offset(0f, bandY), size = Size(w, 6.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            for (ev in listOf(latitude.shadow, latitude.highlight)) {
                if (ev !in -8.0..8.0) continue
                drawLine(
                    SpStyle.primary.copy(alpha = 0.8f), Offset(x(ev), 1f), Offset(x(ev), bandY + 6.dp.toPx()),
                    strokeWidth = 0.75.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
                )
                drawLabel(measurer, "%+.2f".format(ev), x(ev), 2f, w, label)
            }
            for (ev in -8..8 step 2) {
                drawLabel(measurer, if (ev > 0) "+$ev" else "$ev", x(ev.toDouble()), 94.dp.toPx(), w, label)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Share(spS(R.string.sp_latitude_below), latitude.below, warns = true)
            Spacer(Modifier.weight(1f))
            Share(spS(R.string.sp_latitude_within), latitude.within, warns = false)
            Spacer(Modifier.weight(1f))
            Share(spS(R.string.sp_latitude_above), latitude.above, warns = true)
        }
        Text(
            spS(R.string.sp_latitude_held).format(latitude.stopsHeld, latitude.shadow, latitude.highlight),
            style = SpDesign.caption, color = SpStyle.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(spS(R.string.sp_latitude_whole_scene), style = SpDesign.instrument, color = SpStyle.secondary, modifier = Modifier.fillMaxWidth())
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer, text: String, cx: Float, top: Float, w: Float, style: androidx.compose.ui.text.TextStyle,
) {
    val m = measurer.measure(text, style)
    val tw = m.size.width.toFloat()
    val edge = 42.dp.toPx()
    val left = when {
        cx < edge -> cx.coerceAtLeast(2f)
        cx > w - edge -> (cx - tw).coerceAtMost(w - 2f - tw)
        else -> cx - tw / 2
    }.coerceIn(0f, maxOf(0f, w - tw))
    drawText(m, topLeft = Offset(left, top))
}

@Composable
private fun Share(title: String, value: Double, warns: Boolean) {
    Text(
        "$title %.1f%%".format(value * 100),
        style = SpDesign.instrument,
        color = if (warns && value >= 0.005) SpStyle.accent else SpStyle.secondary,
    )
}

/** The graph's slot in the 拍摄 panel: measuring, the graph, or the failure with a retry. */
@Composable
internal fun SpLatitudeSection(ui: SpLatitudeUi, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 152.dp).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        when (ui) {
            SpLatitudeUi.Idle, SpLatitudeUi.Measuring -> Text(spS(R.string.sp_latitude_measuring), style = SpDesign.caption, color = SpStyle.secondary)
            is SpLatitudeUi.Ready -> SpLatitudeGraph(ui.latitude)
            is SpLatitudeUi.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spS(R.string.sp_latitude_failed).format(ui.message), style = SpDesign.caption, color = SpStyle.secondary, textAlign = TextAlign.Center)
                SpTextAction(spS(R.string.sp_latitude_retry), onClick = onRetry)
            }
        }
    }
}
