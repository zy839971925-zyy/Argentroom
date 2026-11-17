// The export and information panels share the editor's existing morph.
// SPDX-License-Identifier: GPL-3.0-or-later
package com.argentroom.app.silverphase

import com.argentroom.app.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.argentroom.app.R

@Composable
internal fun SpPhoneModal(
    open: Boolean,
    origin: SpOrigin?,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val morph = rememberSpMorphState(reduceMotion)
    val currentOrigin by rememberUpdatedState(origin)
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(open) {
        if (open) morph.open(currentOrigin)
        else if (morph.visible) morph.close()
    }
    if (!open && !morph.visible) return
    BackHandler(enabled = open) { dismiss() }
    Box(Modifier.fillMaxSize()) {
        if (open) {
            Box(Modifier.fillMaxSize().background(SpStyle.ink.copy(alpha = 0.26f))
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val density = LocalDensity.current
            val gutter = with(density) { SpDesign.gutter.toPx() }
            val width = with(density) { maxWidth.toPx() }
            val height = with(density) { maxHeight.toPx() }
            val panelHeight = minOf(height * 0.85f, with(density) { 640.dp.toPx() })
            val target = Rect(Offset(gutter, height - gutter - panelHeight),
                Size((width - gutter * 2).coerceAtLeast(1f), panelHeight.coerceAtLeast(1f)))
            SpMorphSurface(morph, { target }, with(density) { 24.dp.toPx() }) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        SpChromeButton(spS(R.string.sp_collapse_panel), SpGlyph.CHEVRON_DOWN, onClick = onDismiss)
                    }
                    Box(Modifier.weight(1f)) { content() }
                }
            }
        }
    }
}
