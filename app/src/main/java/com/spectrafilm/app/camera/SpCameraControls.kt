/*
 * Argentroom camera — finder and dock controls (glass buttons, menus, zoom chips, shutter, last shot, film strip). GPLv3.
 * Film modeling powered by spektrafilm.
 * Split out of SpCameraScreen.kt; see that file for the screen's design notes.
 */
package com.spectrafilm.app.camera

import com.spectrafilm.app.*
import com.spectrafilm.app.diagnostics.*
import com.spectrafilm.app.editor.*
import com.spectrafilm.app.export.*
import com.spectrafilm.app.settings.*
import androidx.camera.core.ImageCapture
import androidx.camera.core.ZoomState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectrafilm.app.R
import com.spectrafilm.app.silverphase.SpCover
import com.spectrafilm.app.silverphase.SpDesign
import com.spectrafilm.app.silverphase.SpGlyph
import com.spectrafilm.app.silverphase.SpGlyphIcon
import com.spectrafilm.app.silverphase.SpMotion
import com.spectrafilm.app.silverphase.SpStock
import com.spectrafilm.app.silverphase.SpStyle
import com.spectrafilm.app.silverphase.spBrowserSelection
import com.spectrafilm.app.silverphase.spChromeSurface
import com.spectrafilm.app.silverphase.spClickable
import kotlin.math.abs
import kotlin.math.max

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SpRoundGlass(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.spChromeSurface(radius = 24.dp), contentAlignment = Alignment.Center, content = content)
}

@Composable
internal fun SpCamButton(
    title: String,
    glyph: SpCamGlyph,
    enabled: Boolean,
    rotation: Float,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> SpStyle.muted
        selected -> SpStyle.accent
        else -> SpStyle.primary.copy(alpha = 0.9f)
    }
    Box(
        Modifier
            .size(SpDesign.hitSize)
            .spClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        SpCamGlyphIcon(glyph, color, Modifier.size(SpDesign.glyphSize).rotate(rotation))
    }
}

@Composable
internal fun FlashButton(mode: Int, enabled: Boolean, rotation: Float, onClick: () -> Unit) {
    val glyph = when (mode) {
        ImageCapture.FLASH_MODE_ON -> SpCamGlyph.BOLT
        ImageCapture.FLASH_MODE_AUTO -> SpCamGlyph.BOLT_AUTO
        else -> SpCamGlyph.BOLT_SLASH
    }
    val value = stringResource(
        when (mode) {
            ImageCapture.FLASH_MODE_ON -> R.string.spcam_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.string.spcam_flash_auto
            else -> R.string.spcam_flash_off
        },
    )
    val title = stringResource(R.string.spcam_flash)
    Box(Modifier.semantics { stateDescription = value }) {
        SpCamButton(
            title = title, glyph = glyph, enabled = enabled, rotation = rotation,
            selected = mode != ImageCapture.FLASH_MODE_OFF, onClick = onClick,
        )
    }
}

@Composable
internal fun MeteringMenu(value: SpCameraMetering, enabled: Boolean, rotation: Float, onChange: (SpCameraMetering) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(value.labelRes)
    Box(Modifier.semantics { stateDescription = label }) {
        SpCamButton(
            title = stringResource(R.string.spcam_metering), glyph = SpCamGlyph.METERING, enabled = enabled,
            rotation = rotation, selected = value != SpCameraMetering.OFF,
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SpStyle.menuSurface) {
            Text(
                stringResource(R.string.spcam_metering),
                style = SpDesign.sectionTitle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for (m in SpCameraMetering.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(m.labelRes), style = SpDesign.parameter, color = SpStyle.primary) },
                    leadingIcon = { CheckSlot(m == value) },
                    onClick = { onChange(m); open = false },
                )
            }
            Text(
                stringResource(R.string.spcam_metering_note),
                style = SpDesign.help, color = SpStyle.secondaryText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 260.dp),
            )
        }
    }
}

@Composable
internal fun CompositionMenu(
    grid: GridMode,
    level: Boolean,
    enabled: Boolean,
    rotation: Float,
    onGrid: (GridMode) -> Unit,
    onLevel: (Boolean) -> Unit,
    /** "Prefer RAW capture", or null when the bound camera cannot capture RAW. */
    raw: Boolean? = null,
    onRaw: (Boolean) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SpCamButton(
            title = stringResource(R.string.spcam_composition), glyph = SpCamGlyph.GRID, enabled = enabled,
            rotation = rotation, selected = grid != GridMode.OFF || level,
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SpStyle.menuSurface) {
            val options = listOf(
                GridMode.OFF to R.string.spcam_grid_off,
                GridMode.THIRDS to R.string.spcam_grid_thirds,
                GridMode.GOLDEN to R.string.spcam_grid_golden,
            )
            for ((mode, res) in options) {
                DropdownMenuItem(
                    text = { Text(stringResource(res), style = SpDesign.parameter, color = SpStyle.primary) },
                    leadingIcon = { CheckSlot(mode == grid) },
                    onClick = { onGrid(mode); open = false },
                )
            }
            HorizontalDivider(color = SpStyle.rule)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.spcam_level), style = SpDesign.parameter, color = SpStyle.primary) },
                leadingIcon = { CheckSlot(level) },
                onClick = { onLevel(!level); open = false },
            )
            if (raw != null) {
                HorizontalDivider(color = SpStyle.rule)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.spcam_prefer_raw), style = SpDesign.parameter, color = SpStyle.primary) },
                    leadingIcon = { CheckSlot(raw) },
                    onClick = { onRaw(!raw); open = false },
                )
            }
        }
    }
}

@Composable
internal fun CheckSlot(checked: Boolean) {
    Box(Modifier.size(14.dp)) {
        if (checked) SpGlyphIcon(SpGlyph.CHECK, SpStyle.primary, Modifier.size(14.dp))
    }
}

@Composable
internal fun SpPill(text: String, accent: Boolean = false, description: String? = null, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = SpDesign.caption,
        color = if (accent) SpStyle.accent else SpStyle.primary.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(SpStyle.ink.copy(alpha = 0.35f))
            .then(if (onClick != null) Modifier.spClickable(onClick = onClick) else Modifier)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
internal fun ZoomChips(zoom: ZoomState?, enabled: Boolean, rotation: Float, onZoom: (Float) -> Unit) {
    val z = zoom ?: return
    val stops = buildList {
        if (z.minZoomRatio < 0.95f) add(z.minZoomRatio)
        add(1f)
        if (z.maxZoomRatio >= 2f) add(2f)
        if (z.maxZoomRatio >= 5f) add(5f)
    }
    if (stops.size < 2 && z.maxZoomRatio <= 1f) return
    // The chip nearest the current ratio shows the live ratio (e.g. 1.4x), as the iOS lens pill.
    val current = z.zoomRatio
    val nearest = stops.minByOrNull { abs(it - current) } ?: 1f
    Row(Modifier.spChromeSurface(radius = 22.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (stop in stops) {
            val selected = stop == nearest
            val shown = if (selected) current else stop
            Text(
                formatZoom(shown),
                style = SpDesign.label.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = if (selected) SpStyle.accent else SpStyle.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(width = SpDesign.iosHitSize, height = SpDesign.iosHitSize)
                    .spClickable(enabled = enabled) { onZoom(stop) }
                    .semantics { this.selected = selected }
                    .rotate(rotation)
                    .padding(top = 13.dp),
            )
        }
    }
}

@Composable
internal fun Shutter(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val title = stringResource(R.string.spcam_shutter)
    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .size(76.dp)
            .spChromeSurface(radius = 38.dp)
            .spClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = title }
            .graphicsLayer { alpha = if (enabled || busy) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(68.dp).border(1.5.dp, SpStyle.primary.copy(alpha = 0.75f), CircleShape))
        Box(Modifier.size(58.dp).clip(CircleShape).background(SpStyle.primary))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), color = SpStyle.ink, strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun LatestShot(
    shot: SpCameraShot?,
    waiting: Int,
    progress: Float?,
    rotation: Float,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val title = stringResource(R.string.spcam_latest)
    Box(
        modifier
            .size(56.dp)
            .spClickable(enabled = shot != null, onClick = onClick)
            .semantics { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        val image = shot?.developed ?: shot?.latent
        Crossfade(targetState = image, animationSpec = tween(420, easing = com.spectrafilm.app.silverphase.SpMotion.EaseOut), label = "latestShot") { img ->
            Box(
                Modifier
                    .size(48.dp)
                    .rotate(rotation)
                    .clip(CircleShape)
                    .background(SpStyle.primary.copy(alpha = 0.12f))
                    .border(1.dp, SpStyle.primary.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (img != null) {
                    Image(
                        img, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            // A shot still in the developer reads as a latent image.
                            alpha = if (shot?.developed == null) 0.55f else 1f
                        },
                    )
                } else {
                    SpGlyphIcon(SpGlyph.PHOTO, SpStyle.primary.copy(alpha = 0.7f), Modifier.size(SpDesign.glyphSize))
                }
            }
        }
        if (waiting > 0) {
            if (progress != null && progress > 0f) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(56.dp),
                    color = SpStyle.accent,
                    strokeWidth = 2.dp,
                    trackColor = SpStyle.primary.copy(alpha = 0.15f),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = SpStyle.accent,
                    strokeWidth = 2.dp,
                    trackColor = SpStyle.primary.copy(alpha = 0.15f),
                )
            }
            if (waiting > 1) {
                Text(
                    waiting.toString(),
                    style = SpDesign.badge,
                    color = SpStyle.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(SpStyle.accent)
                        .padding(top = 2.dp),
                )
            }
        } else if (shot?.phase == SpShotPhase.FAILED) {
            Box(
                Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(SpStyle.destructive),
            )
        }
    }
}

@Composable
internal fun FilmButton(name: String, open: Boolean, enabled: Boolean, rotation: Float, onClick: () -> Unit) {
    val title = stringResource(R.string.spcam_choose_film)
    Column(
        Modifier
            .widthIn(max = 104.dp)
            .spClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = title; stateDescription = name },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).spChromeSurface(radius = 22.dp), contentAlignment = Alignment.Center) {
            SpGlyphIcon(
                SpGlyph.FILM,
                if (open) SpStyle.accent else if (enabled) SpStyle.primary else SpStyle.muted,
                Modifier.size(SpDesign.glyphSize).rotate(rotation),
            )
        }
        Text(
            name, style = SpDesign.caption, color = SpStyle.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

internal val CameraCardLineStyle = androidx.compose.ui.text.style.LineHeightStyle(
    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
)
internal val CameraCardTitle = SpDesign.cardTitle.copy(
    lineHeight = 14.sp, lineHeightStyle = CameraCardLineStyle,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
)
internal val CameraCardCaption = SpDesign.caption.copy(
    lineHeight = 12.sp, lineHeightStyle = CameraCardLineStyle,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
)

@Composable
internal fun FilmStrip(films: List<SpStock>, selectedId: String, onPick: (SpStock) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        val index = films.indexOfFirst { it.id == selectedId }
        if (index > 0) state.scrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = state,
        // Wraps its cards (at least 84 dp + padding) so a larger system font never clips them.
        modifier = Modifier.fillMaxWidth().heightIn(min = SpDesign.cardHeight + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        items(films, key = { it.id }) { stock ->
            val selected = stock.id == selectedId
            Column(
                Modifier
                    .width(104.dp)
                    .heightIn(min = SpDesign.cardHeight)
                    .spBrowserSelection(selected)
                    .spClickable { onPick(stock) }
                    .semantics { contentDescription = stock.name; this.selected = selected }
                    .padding(SpDesign.cardInset),
            ) {
                SpCover(
                    stock.coverAsset, SpGlyph.FILM,
                    Modifier.fillMaxWidth().height(SpDesign.artworkHeight).clip(RoundedCornerShape(SpDesign.artworkRadius)),
                    reqWidth = 96.dp,
                )
                // Silverphase BrowserCard: title on a fixed 14 pt line, subtitle on a fixed 12 pt
                // line, 4 pt under the artwork. Compose's default line height plus font padding
                // overran the 84 dp card and clipped the name (1.0.1); fixed, trimmed line boxes
                // keep both lines inside it at any font scale, and a long name ellipsizes.
                Text(
                    stock.name, style = CameraCardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                )
                Text(
                    stock.speedLabel.orEmpty(), style = CameraCardCaption, color = SpStyle.secondaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
