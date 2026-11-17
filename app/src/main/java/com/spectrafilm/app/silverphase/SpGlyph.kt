/*
 * Silverphase Android port — glyphs. GPLv3.
 *
 * SF Symbols are Apple-licensed and cannot ship on Android (UI_FIDELITY §7.2). These are
 * newly drawn line glyphs on a 20-unit grid that follow the optical weight of the SF
 * "regular" symbols Silverphase uses (1.6 unit stroke, round caps), so the toolbar keeps the
 * same visual mass. Each case names the SF Symbol it stands in for.
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.editor.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class SpGlyph {
    BACK,            // chevron.backward
    EXPORT,          // square.and.arrow.up
    MENU,            // ellipsis.circle
    CLOSE,           // xmark
    SEARCH,          // magnifyingglass
    CHECK,           // checkmark
    CHEVRON_RIGHT,   // chevron.right
    CHEVRON_DOWN,    // chevron.down
    UP_DOWN,         // chevron.up.chevron.down
    STAR,            // star
    STAR_FILL,       // star.fill
    FLAG,            // flag
    FILM,            // film
    FILM_STACK,      // film.stack
    PHOTO,           // photo
    APERTURE,        // camera.aperture
    DROP,            // drop.fill
    PRINT,           // photo.on.rectangle.angled
    SCANNER,         // scanner
    ASPECT,          // aspectratio
    DASHED,          // rectangle.dashed
    PLUS,            // plus
    CAMERA,          // camera
    FILTER,          // line.3.horizontal.decrease.circle
    SELECT,          // checkmark.circle
    EYEDROPPER,      // eyedropper
}

@Composable
internal fun SpGlyphIcon(glyph: SpGlyph, color: Color, modifier: Modifier) {
    Canvas(modifier) { drawGlyph(glyph, color) }
}

private fun DrawScope.drawGlyph(g: SpGlyph, c: Color) {
    val u = size.minDimension / 20f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(vararg pts: Pair<Float, Float>) {
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u) }
        drawPath(path, c, style = stroke)
    }
    fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float, fill: Boolean = false, dashed: Boolean = false) {
        val st = if (dashed) Stroke(1.6f * u, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.2f * u, 2.2f * u)))
        else stroke
        if (fill) drawRoundRect(c, p(l, t), Size((r - l) * u, (b - t) * u), CornerRadius(rad * u))
        else drawRoundRect(c, p(l, t), Size((r - l) * u, (b - t) * u), CornerRadius(rad * u), style = st)
    }
    fun star(fill: Boolean) {
        val path = Path()
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rad = if (i % 2 == 0) 8.6f else 3.7f
            val x = 10f + rad * cos(a).toFloat(); val y = 10.6f + rad * sin(a).toFloat()
            if (i == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u)
        }
        path.close()
        if (fill) drawPath(path, c) else drawPath(path, c, style = stroke)
    }
    when (g) {
        SpGlyph.BACK -> line(12.5f to 3.5f, 6f to 10f, 12.5f to 16.5f)
        SpGlyph.EXPORT -> {
            // Optical correction as ToolbarGlyph: the arrow lifts the bounds, so the body sits 2 units up.
            line(7f to 8f, 4.5f to 8f, 4.5f to 18f, 15.5f to 18f, 15.5f to 8f, 13f to 8f)
            line(10f to 12f, 10f to 1.5f); line(6.8f to 4.6f, 10f to 1.5f, 13.2f to 4.6f)
        }
        SpGlyph.MENU -> {
            drawCircle(c, 8.5f * u, p(10f, 10f), style = stroke)
            for (x in listOf(6f, 10f, 14f)) drawCircle(c, 1.15f * u, p(x, 10f))
        }
        SpGlyph.CLOSE -> { line(4.5f to 4.5f, 15.5f to 15.5f); line(15.5f to 4.5f, 4.5f to 15.5f) }
        SpGlyph.SEARCH -> { drawCircle(c, 6f * u, p(8.5f, 8.5f), style = stroke); line(13f to 13f, 17.5f to 17.5f) }
        SpGlyph.CHECK -> line(3.5f to 10.5f, 8f to 15f, 16.5f to 5f)
        SpGlyph.CHEVRON_RIGHT -> line(7.5f to 3.5f, 14f to 10f, 7.5f to 16.5f)
        SpGlyph.CHEVRON_DOWN -> line(3.5f to 7.5f, 10f to 14f, 16.5f to 7.5f)
        SpGlyph.UP_DOWN -> { line(5.5f to 7.5f, 10f to 3f, 14.5f to 7.5f); line(5.5f to 12.5f, 10f to 17f, 14.5f to 12.5f) }
        SpGlyph.STAR -> star(false)
        SpGlyph.STAR_FILL -> star(true)
        SpGlyph.FLAG -> { line(4.5f to 18f, 4.5f to 2.5f); line(4.5f to 3f, 15.5f to 3f, 13f to 7f, 15.5f to 11f, 4.5f to 11f) }
        SpGlyph.FILM -> {
            rrect(3f, 2f, 17f, 18f, 2f)
            line(6.5f to 2f, 6.5f to 18f); line(13.5f to 2f, 13.5f to 18f)
            for (y in listOf(5f, 8.3f, 11.7f, 15f)) { drawCircle(c, 0.75f * u, p(4.75f, y)); drawCircle(c, 0.75f * u, p(15.25f, y)) }
        }
        SpGlyph.FILM_STACK -> {
            rrect(3f, 5f, 15f, 18f, 2f)
            line(6f to 5f, 6f to 18f); line(12f to 5f, 12f to 18f)
            line(6f to 2.5f, 17.5f to 2.5f, 17.5f to 14.5f)
        }
        SpGlyph.PHOTO -> {
            rrect(2f, 3.5f, 18f, 16.5f, 2.5f)
            line(2.5f to 14f, 7.5f to 9f, 11f to 12.5f, 13.5f to 10.5f, 17.5f to 14f)
            drawCircle(c, 1.4f * u, p(13.5f, 7f))
        }
        SpGlyph.APERTURE -> {
            drawCircle(c, 8.5f * u, p(10f, 10f), style = stroke)
            for (i in 0 until 6) {
                val a = i * PI / 3
                val x1 = 10f + 8.3f * cos(a).toFloat(); val y1 = 10f + 8.3f * sin(a).toFloat()
                val a2 = a + PI / 3 + 0.55
                val x2 = 10f + 3.6f * cos(a2).toFloat(); val y2 = 10f + 3.6f * sin(a2).toFloat()
                line(x1 to y1, x2 to y2)
            }
        }
        SpGlyph.DROP -> {
            val path = Path().apply {
                moveTo(10f * u, 1.8f * u)
                cubicTo(13.5f * u, 6.5f * u, 16f * u, 9.5f * u, 16f * u, 12.5f * u)
                cubicTo(16f * u, 16f * u, 13.3f * u, 18.5f * u, 10f * u, 18.5f * u)
                cubicTo(6.7f * u, 18.5f * u, 4f * u, 16f * u, 4f * u, 12.5f * u)
                cubicTo(4f * u, 9.5f * u, 6.5f * u, 6.5f * u, 10f * u, 1.8f * u)
                close()
            }
            drawPath(path, c)
        }
        SpGlyph.PRINT -> {
            // A rotated back print behind a front photo.
            line(5.5f to 4.5f, 15f to 2.2f, 17.8f to 13.5f)
            rrect(2f, 6.5f, 14.5f, 17.5f, 2f)
            line(2.5f to 15.5f, 6.5f to 11.5f, 9.5f to 14.2f, 11.5f to 12.5f, 14f to 15f)
        }
        SpGlyph.SCANNER -> {
            rrect(2f, 9f, 18f, 17f, 2.2f)
            line(2.5f to 12.5f, 17.5f to 12.5f)
            line(4f to 8.5f, 15.5f to 3f)
            drawCircle(c, 0.9f * u, p(14.5f, 15f))
        }
        SpGlyph.ASPECT -> {
            rrect(2f, 4f, 18f, 16f, 2.5f)
            line(5.5f to 10f, 5.5f to 7.5f, 8.5f to 7.5f); line(14.5f to 10f, 14.5f to 12.5f, 11.5f to 12.5f)
        }
        SpGlyph.DASHED -> rrect(2.5f, 4.5f, 17.5f, 15.5f, 2f, dashed = true)
        SpGlyph.PLUS -> { line(10f to 3.5f, 10f to 16.5f); line(3.5f to 10f, 16.5f to 10f) }
        SpGlyph.CAMERA -> {
            val path = Path().apply {
                moveTo(2f * u, 7f * u); lineTo(2f * u, 16f * u); lineTo(18f * u, 16f * u); lineTo(18f * u, 7f * u)
                lineTo(14f * u, 7f * u); lineTo(12.5f * u, 4.5f * u); lineTo(7.5f * u, 4.5f * u); lineTo(6f * u, 7f * u); close()
            }
            drawPath(path, c, style = stroke)
            drawCircle(c, 3.3f * u, p(10f, 11.3f), style = stroke)
        }
        SpGlyph.FILTER -> {
            drawCircle(c, 8.5f * u, p(10f, 10f), style = stroke)
            line(5.5f to 7.3f, 14.5f to 7.3f); line(7f to 10.3f, 13f to 10.3f); line(8.6f to 13.3f, 11.4f to 13.3f)
        }
        SpGlyph.SELECT -> { drawCircle(c, 8.5f * u, p(10f, 10f), style = stroke); line(6f to 10.3f, 8.8f to 13f, 14f to 7.3f) }
        SpGlyph.EYEDROPPER -> {
            line(3.5f to 16.5f, 5f to 13f, 11.5f to 6.5f); line(8.8f to 4f, 16f to 11.2f)
            drawRoundRect(c, p(12f, 1.8f), Size(5.5f * u, 5.5f * u), CornerRadius(2.75f * u))
        }
    }
}

/** The bounds helper some callers use for hit-testing a glyph's visual box. */
internal fun glyphBox(sizePx: Float): Rect = Rect(0f, 0f, sizePx, sizePx)
