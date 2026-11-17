/*
 * Silverphase Android port — inspector rows. GPLv3.
 * ProcessControls.swift's section(), flag(), choice() and help rows, plus ToolSectionTitle
 * (EditorDesign.swift). Choices use a native popup menu, as iOS uses the system Menu/Picker
 * ("参数选择使用系统Menu／Picker，不另绘普通选项浮层").
 */
package com.argentroom.app.silverphase

import com.argentroom.app.*
import com.argentroom.app.editor.*
import com.argentroom.app.library.*
import com.argentroom.app.look.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.annotation.StringRes
import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.sp
import com.argentroom.app.R
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * String resources for the Silverphase UI, readable outside composition (menu rows are built in
 * click handlers). Bound in MainActivity.onCreate before any UI exists, and again by the phone
 * editor on each composition so a recreated Activity (locale change) is picked up. Holds only a
 * Resources object, never a Context/Activity.
 */
internal object SpText {
    @Volatile var res: Resources? = null
        private set
    fun bind(r: Resources) { if (res !== r) res = r }
}
internal fun spS(@StringRes id: Int, vararg args: Any): String =
    SpText.res?.let { if (args.isEmpty()) it.getString(id) else it.getString(id, *args) } ?: ""

/** Inline help, toggled by tapping the label's ⓘ or long-pressing the row (Android addition). */
@Composable
private fun SpHelpReveal(visible: Boolean, @StringRes help: Int?) {
    if (help == null) return
    AnimatedVisibility(
        visible,
        enter = expandVertically(SpMotion.smooth028(IntSize.VisibilityThreshold)) + fadeIn(SpMotion.easeOut(180)),
        exit = fadeOut(SpMotion.easeOut(140)) + shrinkVertically(SpMotion.smooth028(IntSize.VisibilityThreshold)),
    ) {
        Text(
            spS(help), style = SpDesign.help, color = SpStyle.secondaryText,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
    }
}

/** The "ⓘ" mark after a label. */
@Composable
internal fun SpInfoMark(open: Boolean) {
    Text(
        " ⓘ", style = SpDesign.help.copy(fontSize = 12.sp),
        color = if (open) SpStyle.accent else SpStyle.muted,
    )
}

/** A label that shows the parameter's help when tapped or long-pressed. */
@Composable
internal fun SpHelpLabel(
    title: String,
    @StringRes help: Int?,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    enabled: Boolean = true,
) {
    Row(
        modifier.heightIn(min = SpDesign.hitSize)
            .then(
                if (help != null) Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = spS(R.string.sp_about_param, title), onLongClick = onToggle, onClick = onToggle,
                ) else Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = SpDesign.parameter, color = SpStyle.secondaryText, maxLines = maxLines,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (help != null) SpInfoMark(open)
    }
}

/** ToolSectionTitle: 24dp before, 10dp after. */
@Composable
internal fun SpSectionTitle(title: String) {
    Text(
        title, style = SpDesign.sectionTitle,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp).semantics { heading() },
    )
}

/** flag(): label left, a 14dp checkbox square right; 48dp row (iOS 44). Long-press shows help. */
@Composable
internal fun SpFlag(title: String, value: Boolean, enabled: Boolean = true, @StringRes help: Int? = null, onChange: (Boolean) -> Unit) {
    var helpOpen by rememberSaveable(title) { mutableStateOf(false) }
    val on = spS(R.string.sp_state_on); val off = spS(R.string.sp_state_off)
    // C05: enabled state, box ring and check animate; the box dips under the finger.
    val rowAlpha by animateFloatAsState(if (enabled) 1f else 0.45f, SpMotion.easeOut(200), label = "flagEnabled")
    val ring by animateColorAsState(if (value) SpStyle.accent else SpStyle.primary.copy(alpha = 0.3f), SpMotion.easeOut(160), label = "flagRing")
    val press = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(SpStyle.row)
                .graphicsLayer { alpha = rowAlpha }
                .combinedClickable(
                    enabled = enabled, role = Role.Checkbox,
                    interactionSource = press, indication = null,
                    onLongClick = if (help != null) ({ helpOpen = !helpOpen }) else null,
                ) { onChange(!value) }
                .semantics { contentDescription = title; stateDescription = if (value) on else off },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = SpDesign.parameter, color = SpStyle.secondaryText, modifier = Modifier.weight(1f, fill = false))
                if (help != null) Box(
                    Modifier.size(SpDesign.hitSize).spClickable { helpOpen = !helpOpen }
                        .semantics { contentDescription = spS(R.string.sp_about_param, title) },
                    contentAlignment = Alignment.CenterStart,
                ) { SpInfoMark(helpOpen) }
            }
            Box(
                Modifier.spPressFeedback(press, enabled, pressedScale = 0.8f).size(14.dp)
                    .border(1.dp, ring, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                SpPopIn(value) { SpGlyphIcon(SpGlyph.CHECK, SpStyle.accent, Modifier.size(9.dp)) }
            }
        }
        SpHelpReveal(helpOpen, help)
    }
}

/**
 * choice(): 92dp label column, then the selected value with chevron.up.chevron.down; the menu
 * grows out of the value (SpOverlay) like UIMenu. Falls back to DropdownMenu outside the phone editor.
 */
@Composable
internal fun SpChoice(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    @StringRes help: Int? = null,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var helpOpen by rememberSaveable(title) { mutableStateOf(false) }
    val overlay = LocalSpOverlay.current
    var origin by remember { mutableStateOf<SpOrigin?>(null) }
    val reduce = LocalSpReduceMotion.current
    val rowAlpha by animateFloatAsState(if (enabled) 1f else 0.45f, SpMotion.easeOut(200), label = "choiceEnabled")
    val press = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = SpDesign.hitSize).graphicsLayer { alpha = rowAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpHelpLabel(title, help, helpOpen, { helpOpen = !helpOpen }, Modifier.width(SpDesign.labelWidth))
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = SpDesign.hitSize)
                        .spOriginOf({ origin = it }, radiusPx = 0f)
                        .clickable(
                            enabled = enabled, role = Role.DropdownList,
                            interactionSource = press, indication = null,
                        ) {
                            val o = origin
                            if (overlay != null && o != null) {
                                overlay.showMenu(o, options.map { (id, label) ->
                                    SpMenuItem(label, checked = id == value) { if (id != value) onChange(id) }
                                })
                            } else open = true
                        }
                        .semantics { contentDescription = title },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // C05: a new choice rises into place (value-change transition).
                    AnimatedContent(
                        options.firstOrNull { it.first == value }?.second ?: value,
                        Modifier.weight(1f, fill = false).spPressFeedback(press, enabled),
                        transitionSpec = spSwapTransition(reduce), contentAlignment = Alignment.CenterStart, label = "choiceValue",
                    ) { label ->
                        Text(label, style = SpDesign.selectedValue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    SpGlyphIcon(SpGlyph.UP_DOWN, SpStyle.primary, Modifier.spPressFeedback(press, enabled, SpMotion.PRESS_SCALE_GLYPH).size(10.dp))
                }
                if (overlay == null) DropdownMenu(
                    expanded = open, onDismissRequest = { open = false },
                    containerColor = SpStyle.menuSurface,
                ) {
                    for ((id, label) in options) {
                        DropdownMenuItem(
                            text = { Text(label, style = SpDesign.parameter, color = SpStyle.primary) },
                            trailingIcon = if (id == value) {
                                { SpGlyphIcon(SpGlyph.CHECK, SpStyle.primary, Modifier.size(14.dp)) }
                            } else null,
                            onClick = { open = false; if (id != value) onChange(id) },
                        )
                    }
                }
            }
        }
        SpHelpReveal(helpOpen, help)
    }
}

/** A 13dp text action row (e.g. 恢复拍摄时白平衡). */
@Composable
internal fun SpTextAction(title: String, enabled: Boolean = true, color: Color = SpStyle.primary, onClick: () -> Unit) {
    val rowAlpha by animateFloatAsState(if (enabled) 1f else 0.45f, SpMotion.easeOut(200), label = "actionEnabled")
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.heightIn(min = SpDesign.hitSize)
            .graphicsLayer { alpha = rowAlpha }
            .clickable(enabled = enabled, role = Role.Button, interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) { Text(title, style = SpDesign.parameter, color = color, modifier = Modifier.spPressFeedback(press, enabled)) }
}

/** Help / caption text, 11dp secondary. */
@Composable
internal fun SpHelp(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SpDesign.help, color = SpStyle.secondaryText, modifier = modifier.fillMaxWidth().padding(vertical = 8.dp))
}

/**
 * A Silverphase control whose engine stage the Android engine does not have yet. The control
 * keeps its place, label and default in the panel so the layout matches iOS, but it is
 * disabled and says so plainly instead of driving a look-alike (handoff: 不允许伪装).
 * Tapping it explains why.
 */
@Composable
internal fun SpUnavailable(title: String, valueText: String, @StringRes help: Int = R.string.sp_help_unavailable) {
    var helpOpen by rememberSaveable(title) { mutableStateOf(false) }
    val desc = spS(R.string.sp_unavailable_desc, title)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = SpDesign.hitSize)
                .spClickable { helpOpen = !helpOpen }
                .semantics { contentDescription = desc },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).graphicsLayer { alpha = 0.45f }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = SpDesign.parameter, color = SpStyle.secondaryText, modifier = Modifier.weight(1f, fill = false))
                SpInfoMark(helpOpen)
            }
            Text(valueText, style = SpDesign.selectedValue, modifier = Modifier.graphicsLayer { alpha = 0.45f })
            Spacer(Modifier.width(8.dp))
            Text(
                spS(R.string.sp_engine_missing), style = SpDesign.badge, color = SpStyle.secondaryText,
                modifier = Modifier.background(SpStyle.well, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        SpHelpReveal(helpOpen, help)
    }
}

/**
 * C02: several neighbouring controls the Android engine lacks (e.g. 影调's four rows) fold into
 * ONE row instead of four disabled rulers, so the working controls stay near the top of a phone
 * panel. Nothing is dropped: tapping the row lists every control with its own explanation.
 */
@Composable
internal fun SpUnavailableGroup(items: List<Pair<String, String>>, @StringRes help: Int = R.string.sp_help_unavailable) {
    if (items.size <= 1) { items.firstOrNull()?.let { SpUnavailable(it.first, it.second, help) }; return }
    var open by rememberSaveable(items.first().first) { mutableStateOf(false) }
    val names = items.joinToString(" · ") { it.first }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = SpDesign.hitSize)
                .spClickable { open = !open }
                .semantics { contentDescription = names },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                names, style = SpDesign.parameter, color = SpStyle.secondaryText, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = 0.45f },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                spS(R.string.sp_engine_missing), style = SpDesign.badge, color = SpStyle.secondaryText,
                modifier = Modifier.background(SpStyle.well, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        // C05: the folded rows unfold / fold instead of popping.
        AnimatedVisibility(
            open,
            enter = expandVertically(SpMotion.smooth028(IntSize.VisibilityThreshold)) + fadeIn(SpMotion.easeOut(180)),
            exit = fadeOut(SpMotion.easeOut(140)) + shrinkVertically(SpMotion.smooth028(IntSize.VisibilityThreshold)),
        ) { Column { items.forEach { (t, v) -> SpUnavailable(t, v, help) } } }
    }
}

/** text(): label left, inline text field right. */
@Composable
internal fun SpTextField(title: String, value: String, @StringRes help: Int? = null, enabled: Boolean = true, placeholder: String? = null, onChange: (String) -> Unit) {
    var helpOpen by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = SpDesign.hitSize), verticalAlignment = Alignment.CenterVertically) {
            SpHelpLabel(title, help, helpOpen, { helpOpen = !helpOpen })
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value, onChange, enabled = enabled, singleLine = true,
                textStyle = SpDesign.selectedValue.copy(textAlign = TextAlign.End),
                cursorBrush = SolidColor(SpStyle.accent),
                modifier = Modifier.weight(1f).heightIn(min = SpDesign.hitSize),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (value.isEmpty()) Text(placeholder?.takeIf { it.isNotBlank() } ?: title, style = SpDesign.selectedValue.copy(color = SpStyle.muted, textAlign = TextAlign.End))
                        inner()
                    }
                },
            )
        }
        SpHelpReveal(helpOpen, help)
    }
}
