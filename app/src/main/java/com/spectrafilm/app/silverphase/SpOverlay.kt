/*
 * Silverphase Android port — menus that grow out of their button. GPLv3.
 * iOS shows UIMenu (Menu { } / pull-down) for the editor's ellipsis menu, the material category
 * and every choice() row; the system menu expands from its button and collapses back into it.
 * Material3 DropdownMenu brings its own fixed tween, so the phone editor uses one overlay host
 * instead: the same SpMorph container, anchored to the button that opened it, 48dp rows.
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.library.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One row of an SpMenu. */
internal data class SpMenuItem(
    val title: String,
    val enabled: Boolean = true,
    val divider: Boolean = false,
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

@Stable
internal class SpOverlayController(val morph: SpMorphState) {
    var items by mutableStateOf<List<SpMenuItem>>(emptyList())
        private set
    var minWidthPx by mutableStateOf(0f)
        private set

    fun showMenu(origin: SpOrigin, items: List<SpMenuItem>) {
        this.items = items
        minWidthPx = origin.rect.width
        morph.open(origin)
    }

    fun dismiss() = morph.close()
}

/** Present inside the phone editor; null elsewhere (callers then fall back to DropdownMenu). */
internal val LocalSpOverlay = staticCompositionLocalOf<SpOverlayController?> { null }

private val ROW = 48.dp
private val MENU_W = 232.dp

/** Draws the open menu above everything in the phone editor. */
@Composable
internal fun BoxScope.SpOverlayHost(controller: SpOverlayController) {
    val morph = controller.morph
    if (!morph.visible) return
    BackHandler(enabled = morph.isOpen) { controller.dismiss() }
    // The anchor is in root coordinates; the menu is placed in this host's coordinates.
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.matchParentSize().onGloballyPositioned { hostOrigin = it.positionInRoot() }) {
        val density = LocalDensity.current
        val hostW = with(density) { maxWidth.toPx() }
        val hostH = with(density) { maxHeight.toPx() }
        val gutter = with(density) { SpDesign.gutter.toPx() }
        val rowPx = with(density) { ROW.toPx() }
        val dividerPx = with(density) { 9.dp.toPx() }
        val items = controller.items
        val contentH = items.sumOf { (rowPx + if (it.divider) dividerPx else 0f).toDouble() }.toFloat() + with(density) { 12.dp.toPx() }
        val w = maxOf(with(density) { MENU_W.toPx() }, controller.minWidthPx).coerceAtMost(hostW - 2 * gutter)
        val anchor = morph.origin?.rect?.translate(-hostOrigin.x, -hostOrigin.y) ?: Rect(Offset(hostW / 2, hostH / 2), Size(1f, 1f))
        val below = hostH - anchor.bottom - gutter
        val above = anchor.top - gutter
        val h = minOf(contentH, maxOf(below, above) - 8f, hostH * 0.6f).coerceAtLeast(rowPx)
        val top = if (below >= h + 8f || below >= above) anchor.bottom + 8f else anchor.top - 8f - h
        val left = (if (anchor.center.x > hostW / 2) anchor.right - w else anchor.left).coerceIn(gutter, maxOf(gutter, hostW - gutter - w))
        val target = Rect(Offset(left, top.coerceIn(gutter, maxOf(gutter, hostH - gutter - h))), Size(w, h))
        // Scrim: a tap outside closes the menu (it never passes through, as with UIMenu).
        if (morph.isOpen) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { controller.dismiss() } })
        SpMorphSurface(morph, { target }, with(density) { 14.dp.toPx() }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                for (item in items) {
                    if (item.divider) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(SpStyle.rule))
                    Row(
                        Modifier.fillMaxWidth().height(ROW)
                            .spClickable(enabled = item.enabled, role = Role.Button) {
                                controller.dismiss(); item.onClick()
                            }
                            .semantics { contentDescription = item.title; if (item.checked) selected = true }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(22.dp)) {
                            if (item.checked) SpGlyphIcon(SpGlyph.CHECK, SpStyle.primary, Modifier.size(14.dp))
                        }
                        Text(
                            item.title, style = SpDesign.parameter, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (item.enabled) SpStyle.primary else SpStyle.muted,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
