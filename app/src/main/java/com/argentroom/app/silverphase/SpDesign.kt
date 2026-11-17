/*
 * Silverphase Android port — design tokens and shared chrome. GPLv3.
 * Ported from Silverphase v1.2.5 (akinal5765-byte/Silverphase @ 061fc495)
 * ios/Silverphase/Sources/EditorDesign.swift, MobileCameraChrome.swift, MobileChrome.swift.
 * Film modeling powered by spektrafilm (Andrea Volpato) via Argentroom for Android.
 *
 * Values are the iOS points re-expressed in dp. iOS pt and Android dp are both ~1/160 inch
 * logical units, so the numbers carry over 1:1 as a starting point; device calibration from
 * real screenshots is a later pass and is recorded in worklog/UI_SCREEN_MAP.md.
 */
package com.argentroom.app.silverphase

import com.argentroom.app.editor.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** EditorDesign.swift, one-for-one. No panel owns a private spacing scale. */
internal object SpDesign {
    val unit = 4.dp
    val gutter = 12.dp
    val contentInset = 16.dp
    val canvasInfoInset = 28.dp
    /** iOS hit size is 44pt; Android uses the 48dp minimum touch target (one-handed use, UI_SCREEN_MAP.md). */
    val hitSize = 48.dp
    val iosHitSize = 44.dp
    val glyphSize = 20.dp
    val filmstripGlyphSize = 18.dp
    val panelHeader = 44.dp
    val cardInset = 6.dp
    val cardRadius = 12.dp
    val artworkRadius = cardRadius - cardInset
    const val materialArtworkAspect = 16f / 9f
    val artworkHeight = 42.dp
    val cardHeight = 84.dp
    val browserGap = 4.dp
    val browserHeight = cardInset * 2 + panelHeader + browserGap + cardHeight
    val labelWidth = 92.dp
    val valueWidth = 48.dp
    val tabsHeight = 56.dp
    val panelMaxHeight = 520.dp
    const val panelMaxFraction = 0.72f
    val panelGrip = 14.dp

    // Inspector typography (DESIGN.md "工具面板文字层级").
    val panelTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    val sectionTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    val parameter = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val selectedValue = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
    val numericValue = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White,
        fontFeatureSettings = "tnum",
    )
    val panelSummary = TextStyle(fontSize = 12.sp)
    val help = TextStyle(fontSize = 11.sp)
    val label = TextStyle(fontSize = 12.sp)
    val caption = TextStyle(fontSize = 11.sp)
    val badge = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    val cardTitle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    val noticeTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    val instrument = TextStyle(fontSize = 10.sp, fontFeatureSettings = "tnum")
}

/** MobileStyle (MobileCameraChrome.swift). */
internal object SpStyle {
    /** UIColor.systemOrange in the dark appearance (#FF9F0A); light is #FF9500. The app is dark-only. */
    val accent = Color(0xFFFF9F0A)
    /** sRGB #181818, every page. */
    val canvas = Color(0xFF181818)
    val radius = 16.dp
    val row = SpDesign.hitSize
    val well = Color.White.copy(alpha = 0.09f)
    val rule = Color.White.copy(alpha = 0.09f)
    /** UIColor.secondaryLabel in dark mode: #EBEBF5 at 60%. */
    val secondaryText = Color(0x99EBEBF5)
    /** .secondary foreground (same token in dark). */
    val secondary = Color(0x99EBEBF5)
    val muted = Color.White.copy(alpha = 0.28f)
    val primary = Color.White
    /** Pure black: scrims, edge fades, and ink on the white shutter / primary pill. */
    val ink = Color.Black
    /** UIColor.tertiarySystemBackground (dark), the pop-up menu surface: #2C2C2E. */
    val menuSurface = Color(0xFF2C2C2E)
    /** UIColor.systemRed in the dark appearance (#FF453A): badges and destructive dots. */
    val destructive = Color(0xFFFF453A)
    /** Liquid-glass morph fill (translucent) when Reduce Transparency is off. */
    val glassFill = Color(0xF5222224)
}

/**
 * ChromeSurface: the one glass surface every floating panel reuses. iOS draws the system
 * Liquid Glass; Android has no equivalent system material, so this is the documented
 * fallback the handoff allows — a dark translucent plane with a hairline rim and a faint top
 * sheen — which is also exactly what iOS shows under Reduce Transparency (solid colour).
 * No second blur layer is ever stacked on top (DESIGN.md: 不额外叠加模糊或另一套玻璃材质).
 */
internal fun Modifier.spChromeSurface(
    radius: Dp = SpStyle.radius,
    shape: Shape = RoundedCornerShape(radius),
    reduceTransparency: Boolean = false,
): Modifier = this
    .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
    .clip(shape)
    .background(if (reduceTransparency) Color(0xFF2A2A2C) else Color(0xE0262628))
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.07f),
            0.35f to Color.White.copy(alpha = 0.015f),
            1f to Color.Transparent,
        ),
    )
    .border(0.75.dp, Color.White.copy(alpha = 0.14f), shape)

/** ChromeButton + ToolbarGlyph: 20x20 glyph centred in a 44x44 hit box. */
@Composable
internal fun SpChromeButton(
    title: String,
    glyph: SpGlyph,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    glyphSize: Dp = SpDesign.glyphSize,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val color = tint ?: when {
        !enabled -> SpStyle.muted
        selected -> SpStyle.accent
        else -> Color.White.copy(alpha = 0.85f)
    }
    val press = remember { MutableInteractionSource() }
    // C05: the tint cross-fades between states and the glyph dips under the finger.
    val shown by androidx.compose.animation.animateColorAsState(color, SpMotion.easeOut(180), label = "chromeTint")
    Box(
        modifier
            .size(SpDesign.hitSize)
            .clickable(
                interactionSource = press,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        SpGlyphIcon(glyph, shown, Modifier.spPressFeedback(press, enabled, SpMotion.PRESS_SCALE_GLYPH).size(glyphSize))
    }
}

/** BrowserSelection: orange 2dp inner stroke + 6% orange fill when selected; never inverted. */
internal fun Modifier.spBrowserSelection(selected: Boolean, radius: Dp = SpDesign.cardRadius): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(if (selected) SpStyle.accent.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.035f))
        .border(if (selected) 2.dp else 1.dp, if (selected) SpStyle.accent else Color.White.copy(alpha = 0.16f), shape)
}

/** [spBrowserSelection] whose ring width, ring colour and fill cross-fade on a change (C05). */
@Composable
internal fun Modifier.spBrowserSelectionAnimated(selected: Boolean, radius: Dp = SpDesign.cardRadius): Modifier {
    val shape = RoundedCornerShape(radius)
    val spec = SpMotion.easeOut<Color>(200)
    val fill by androidx.compose.animation.animateColorAsState(
        if (selected) SpStyle.accent.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.035f), spec, label = "selFill",
    )
    val ring by androidx.compose.animation.animateColorAsState(
        if (selected) SpStyle.accent else Color.White.copy(alpha = 0.16f), spec, label = "selRing",
    )
    val width by androidx.compose.animation.core.animateDpAsState(if (selected) 2.dp else 1.dp, SpMotion.easeOut(200), label = "selWidth")
    return this.clip(shape).background(fill).border(width, ring, shape)
}

/** Plain tap target without ripple (iOS buttons in this UI have no ink). */
internal fun Modifier.spClickable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier =
    composed {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null, enabled = enabled, role = role, onClick = onClick,
        )
    }
