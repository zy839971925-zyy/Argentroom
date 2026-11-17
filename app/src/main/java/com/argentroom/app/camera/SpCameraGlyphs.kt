/*
 * Argentroom camera — the camera-only line glyphs. GPLv3.
 *
 * Same drawing rules as silverphase/SpGlyph.kt (20-unit grid, 1.6-unit round stroke), which
 * stands in for SF Symbols that cannot ship on Android. Each case names the SF Symbol the iOS
 * camera uses there. Kept in their own file so SpGlyph stays the editor's.
 */
package com.argentroom.app.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class SpCamGlyph {
    BOLT,          // bolt.fill
    BOLT_SLASH,    // bolt.slash
    BOLT_AUTO,     // bolt.badge.automatic
    FLIP,          // camera.rotate
    GRID,          // grid
    METERING,      // camera.metering.center.weighted
    SUN,           // sun.max.fill
    LOCK,          // lock.fill
}

@Composable
internal fun SpCamGlyphIcon(glyph: SpCamGlyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 20f
        val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
            if (close) close()
        }
        fun bolt(fill: Boolean, ox: Float = 0f, scale: Float = 1f) {
            val pts = listOf(11.5f to 1.8f, 4.5f to 11.2f, 9.6f to 11.2f, 8.5f to 18.2f, 15.5f to 8.8f, 10.4f to 8.8f)
                .map { (x, y) -> (ox + (x - 10f) * scale + 10f) to ((y - 10f) * scale + 10f) }
            val bp = path(*pts.toTypedArray(), close = true)
            if (fill) drawPath(bp, color) else drawPath(bp, color, style = stroke)
        }
        when (glyph) {
            SpCamGlyph.BOLT -> bolt(fill = true)
            SpCamGlyph.BOLT_SLASH -> {
                bolt(fill = false)
                drawLine(color, p(3.5f, 3.5f), p(16.5f, 16.5f), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
            }
            SpCamGlyph.BOLT_AUTO -> {
                bolt(fill = true, ox = -2.5f, scale = 0.86f)
                // A small "A" badge, lower right.
                drawPath(path(13.2f to 18.5f, 15.6f to 12.2f, 18f to 18.5f), color, style = Stroke(1.3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(color, p(14.1f, 16.4f), p(17.1f, 16.4f), strokeWidth = 1.2f * u, cap = StrokeCap.Round)
            }
            SpCamGlyph.FLIP -> {
                // camera.rotate: the camera body with two turning arrows inside.
                drawPath(
                    path(2f to 7f, 2f to 16f, 18f to 16f, 18f to 7f, 14f to 7f, 12.5f to 4.5f, 7.5f to 4.5f, 6f to 7f, close = true),
                    color, style = stroke,
                )
                drawArc(color, 200f, 130f, false, topLeft = p(6.6f, 8f), size = androidx.compose.ui.geometry.Size(6.8f * u, 6.8f * u), style = stroke)
                drawArc(color, 20f, 130f, false, topLeft = p(6.6f, 8f), size = androidx.compose.ui.geometry.Size(6.8f * u, 6.8f * u), style = stroke)
                drawPath(path(12.6f to 8.6f, 13.6f to 10.2f, 11.9f to 10.4f), color, style = stroke)
                drawPath(path(7.4f to 14.6f, 6.4f to 13f, 8.1f to 12.8f), color, style = stroke)
            }
            SpCamGlyph.GRID -> {
                drawRoundRect(color, p(2.5f, 2.5f), androidx.compose.ui.geometry.Size(15f * u, 15f * u),
                    androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke)
                for (f in listOf(7.5f, 12.5f)) {
                    drawLine(color, p(f, 2.5f), p(f, 17.5f), strokeWidth = 1.2f * u)
                    drawLine(color, p(2.5f, f), p(17.5f, f), strokeWidth = 1.2f * u)
                }
            }
            SpCamGlyph.METERING -> {
                drawRoundRect(color, p(2f, 3.5f), androidx.compose.ui.geometry.Size(16f * u, 13f * u),
                    androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = stroke)
                drawCircle(color, 3.6f * u, p(10f, 10f), style = stroke)
                drawCircle(color, 1.3f * u, p(10f, 10f))
            }
            SpCamGlyph.SUN -> {
                drawCircle(color, 3.8f * u, p(10f, 10f))
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    drawLine(
                        color,
                        p(10f + 6.2f * cos(a).toFloat(), 10f + 6.2f * sin(a).toFloat()),
                        p(10f + 8.4f * cos(a).toFloat(), 10f + 8.4f * sin(a).toFloat()),
                        strokeWidth = 1.6f * u, cap = StrokeCap.Round,
                    )
                }
            }
            SpCamGlyph.LOCK -> {
                drawRoundRect(color, p(4f, 9f), androidx.compose.ui.geometry.Size(12f * u, 9f * u),
                    androidx.compose.ui.geometry.CornerRadius(2f * u))
                drawPath(path(6.8f to 9f, 6.8f to 6.2f), color, style = stroke)
                drawArc(color, 180f, 180f, false, topLeft = p(6.8f, 3f), size = androidx.compose.ui.geometry.Size(6.4f * u, 6.4f * u), style = stroke)
                drawPath(path(13.2f to 6.2f, 13.2f to 9f), color, style = stroke)
            }
        }
    }
}
