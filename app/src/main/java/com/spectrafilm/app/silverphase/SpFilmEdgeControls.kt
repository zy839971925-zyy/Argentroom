// Silverphase iOS film edge and date-back controls. SPDX-License-Identifier: GPL-3.0-or-later
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.*
import com.spectrafilm.app.editor.*
import com.spectrafilm.app.look.*
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.spectrafilm.app.look.OperationalParamLimits
import com.spectrafilm.app.look.ParamsState
import com.spectrafilm.app.R
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
internal fun SpFilmEdgeControls(state: ParamsState, enabled: Boolean, onInteraction: (Boolean) -> Unit) {
    val edge = state.filmEdge

    @Composable
    fun choices(@StringRes title: Int, value: String, vararg options: Pair<String, Int>, change: (String) -> Unit) {
        SpChoice(spS(title), value, options.map { (id, label) -> id to spS(label) }, enabled = enabled, onChange = change)
    }

    @Composable
    fun number(@StringRes title: Int, value: Float, lo: Double, hi: Double, default: Double,
               step: Double = 0.1, digits: Int = 1, change: (Float) -> Unit) {
        SpRulerRow(spS(title), value.toDouble(), lo, hi, default,
            onValue = { change(it.toFloat()) }, onReset = { change(default.toFloat()) },
            onInteraction = onInteraction, fractionDigits = digits, step = step, enabled = enabled)
    }

    SpSectionTitle(spS(R.string.sp_film_edge))
    SpFlag(spS(R.string.sp_edge_show), edge.active, enabled) { state.filmEdge = edge.copy(active = it) }
    choices(R.string.sp_edge_format, edge.format,
        "135" to R.string.sp_edge_135, "135_half" to R.string.sp_edge_half,
        "120_645" to R.string.sp_edge_645, "120_6x6" to R.string.sp_edge_6x6,
        "120_6x7" to R.string.sp_edge_6x7, "120_6x8" to R.string.sp_edge_6x8,
        "120_6x9" to R.string.sp_edge_6x9, "135_xpan" to R.string.sp_edge_xpan,
        "120_6x12" to R.string.sp_edge_6x12, "120_6x17" to R.string.sp_edge_6x17,
    ) { state.filmEdge = edge.copy(format = it) }
    choices(R.string.sp_edge_mode, edge.mode, "strip" to R.string.sp_edge_strip, "filed" to R.string.sp_edge_filed) {
        state.filmEdge = edge.copy(mode = it)
    }
    choices(R.string.sp_edge_gate, edge.gate,
        "auto" to R.string.sp_edge_auto, "square" to R.string.sp_edge_square,
        "rounded" to R.string.sp_edge_rounded, "eared" to R.string.sp_edge_eared,
        "shouldered" to R.string.sp_edge_shouldered, "kicked" to R.string.sp_edge_kicked,
    ) { state.filmEdge = edge.copy(gate = it) }
    choices(R.string.sp_edge_holes, edge.holes, "white" to R.string.sp_edge_white, "black" to R.string.sp_edge_black) {
        state.filmEdge = edge.copy(holes = it)
    }
    number(R.string.sp_edge_frame_number, edge.frameNumber.toFloat(), 0.0, 99.0, 0.0, 1.0, 0) {
        state.filmEdge = edge.copy(frameNumber = it.roundToInt())
    }
    number(R.string.sp_edge_fog, edge.fog, 0.0, 4.0, 1.0) { state.filmEdge = edge.copy(fog = it) }
    number(R.string.sp_edge_leaks, edge.leaks, 0.0, 4.0, 0.0) { state.filmEdge = edge.copy(leaks = it) }
    choices(R.string.sp_edge_carrier, edge.carrier,
        "none" to R.string.sp_edge_none, "black" to R.string.sp_edge_black, "open" to R.string.sp_edge_open,
    ) { state.filmEdge = edge.copy(carrier = it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        EdgeAction(R.string.sp_edge_body, enabled) {
            state.filmEdge = edge.copy(cameraSeed = rollDifferentSeed(edge.cameraSeed, 999))
        }
        EdgeAction(R.string.sp_edge_advance, enabled) {
            state.filmEdge = edge.copy(frameSeed = rollDifferentSeed(edge.frameSeed, 999999))
        }
    }
    SpSectionTitle(spS(R.string.sp_date_back))
    SpFlag(spS(R.string.sp_date_exposure), edge.dateActive, enabled, help = R.string.sp_help_date) {
        state.filmEdge = edge.copy(dateActive = it)
        if (it) state.dateStampActive = false
    }
    // Blank text = the photo's own capture date (or data line), read from its EXIF; show what
    // will be imprinted so the automatic reading is visible, as Silverphase's date back does.
    val autoDate = state.filmEdgeCapture.resolve(edge.copy(dateActive = true), state.filmProfile).renderDateText
    SpTextField(
        spS(R.string.sp_date_text), edge.dateText, enabled = enabled,
        placeholder = autoDate.ifBlank { spS(R.string.sp_date_no_capture) },
    ) {
        state.filmEdge = edge.copy(dateText = it.take(OperationalParamLimits.FILM_EDGE_DATE_TEXT_MAX))
    }
    choices(R.string.sp_edge_date_style, edge.dateStyle,
        "lcd" to R.string.sp_edge_date_lcd, "dots" to R.string.sp_edge_date_dots, "data" to R.string.sp_edge_date_data,
    ) { state.filmEdge = edge.copy(dateStyle = it) }
    number(R.string.sp_date_exposure_ev, edge.dateExposureEv, -2.0, 8.0, 3.5) {
        state.filmEdge = edge.copy(dateExposureEv = it)
    }
    number(R.string.sp_date_size, edge.dateSize, 0.4, 3.0, 1.0) { state.filmEdge = edge.copy(dateSize = it) }
    choices(R.string.sp_edge_date_order, edge.dateOrder,
        "japan" to R.string.sp_edge_date_japan, "us" to R.string.sp_edge_date_us,
    ) { state.filmEdge = edge.copy(dateOrder = it) }
    SpFlag(spS(R.string.sp_edge_date_with_data), edge.dateWithData, enabled) {
        state.filmEdge = edge.copy(dateWithData = it)
    }
    choices(R.string.sp_edge_date_placement, edge.datePlacement,
        "frame" to R.string.sp_edge_date_frame, "rebate" to R.string.sp_film_edge,
    ) { state.filmEdge = edge.copy(datePlacement = it) }
    choices(R.string.sp_date_corner, edge.dateCorner,
        "br" to R.string.sp_bottom_right, "bl" to R.string.sp_bottom_left,
        "tr" to R.string.sp_top_right, "tl" to R.string.sp_top_left,
    ) { state.filmEdge = edge.copy(dateCorner = it) }
    number(R.string.sp_edge_date_inset_x, edge.dateInsetX, 0.0, 30.0, 3.0) {
        state.filmEdge = edge.copy(dateInsetX = it)
    }
    number(R.string.sp_edge_date_inset_y, edge.dateInsetY, 0.0, 30.0, 2.4) {
        state.filmEdge = edge.copy(dateInsetY = it)
    }
    SpHelp(spS(R.string.sp_edge_date_help))
}

private fun rollDifferentSeed(current: Int, max: Int): Int {
    val seed = Random.nextInt(1, max)
    return if (seed >= current) seed + 1 else seed
}

@Composable
private fun EdgeAction(@StringRes title: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = SpDesign.hitSize).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(spS(title), style = SpDesign.selectedValue, color = if (enabled) SpStyle.accent else SpStyle.muted)
    }
}
