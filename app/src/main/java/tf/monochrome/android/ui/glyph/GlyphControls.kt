// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.glassSwitchColors
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * A choice of a few, as one pill of the app's glass with the chosen part
 * filled: Trypt Mania's segmented control.
 *
 * The chosen segment is a solid thumb in the theme's ink with the label
 * knocked out of it, never an accent-coloured label: where a theme's accent
 * is grey, an accent label reads as the dimmest thing in the row, and the
 * chosen chip looked like the disabled one. The thumb slides between segments.
 */
@Composable
fun <T> GlyphSegmented(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    /** What the group chooses, for a screen reader. */
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val scheme = MaterialTheme.colorScheme
    val index = options.indexOf(selected)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .liquidGlass(shape = MonoDimens.shapePill)
            .semantics { contentDescription = description }
            .selectableGroup(),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val thumbX by animateDpAsState(
            targetValue = segment * index.coerceAtLeast(0),
            animationSpec = tween(THUMB_MILLIS),
            label = "segmentThumb",
        )
        if (index >= 0) {
            Box(
                modifier = Modifier
                    .offset(x = thumbX)
                    .width(segment)
                    .fillMaxHeight()
                    .padding(THUMB_INSET)
                    .background(scheme.onSurface.copy(alpha = 0.92f), MonoDimens.shapePill),
            )
        }
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { i, option ->
                val chosen = i == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(MonoDimens.shapePill)
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(option) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = textStyle,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                        color = if (chosen) scheme.surface else scheme.onSurface.copy(alpha = 0.86f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * A heading over a control and, on its right, what the current choice
 * means: the caption the design puts beside each option.
 */
@Composable
fun GlyphControlHeader(label: String, caption: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = MonoDimens.spacingSm),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A value between two round glass buttons, less and more: the speed, the
 * rate, the offset. The value is announced as it changes.
 */
@Composable
fun GlyphStepper(
    value: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    lessLabel: String,
    moreLabel: String,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 52.dp,
    valueStyle: TextStyle = MaterialTheme.typography.headlineLarge,
    lessEnabled: Boolean = true,
    moreEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
    ) {
        RoundGlassButton(
            onClick = onLess,
            size = buttonSize,
            label = lessLabel,
            enabled = lessEnabled,
        ) { Icon(Icons.Default.Remove, contentDescription = null, tint = it) }
        Text(
            text = value,
            style = valueStyle,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        RoundGlassButton(
            onClick = onMore,
            size = buttonSize,
            label = moreLabel,
            enabled = moreEnabled,
        ) { Icon(Icons.Default.Add, contentDescription = null, tint = it) }
    }
}

/** A glass disc that is a button, its glyph in the glass's ink. */
@Composable
fun RoundGlassButton(
    onClick: () -> Unit,
    size: Dp,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
) {
    GlassPill(
        onClick = onClick,
        hazeState = LocalGlassBackdrop.current,
        height = size,
        enabled = enabled,
        onClickLabel = label,
        modifier = modifier
            .width(size)
            .semantics { contentDescription = label },
    ) {
        Box(modifier = Modifier.align(Alignment.Center)) {
            val scheme = MaterialTheme.colorScheme
            content(if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f))
        }
    }
}

/**
 * A setting that is on or off: its name and what it does, and a switch, the
 * whole row the target. The switch's "on" is the theme's ink, for the same
 * reason the segmented control's thumb is.
 */
@Composable
fun GlyphSwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(MonoDimens.shapeMd)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = MonoDimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(MonoDimens.spacingMd))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = glassSwitchColors(accent = MaterialTheme.colorScheme.onSurface),
        )
    }
}

/**
 * A setting on a scale: its name and reading over a slider. The thumb and
 * the filled track are the theme's ink, as a switch's "on" is; the reading is
 * what a screen reader says for the value, so it hears "3 px", not "37%".
 */
@Composable
fun GlyphSlider(
    label: String,
    value: Float,
    reading: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    note: String? = null,
    /** When set, a reset beside the reading puts the slider back to it. */
    default: Float? = null,
    /** Called once the slider is let go. */
    onChangeFinished: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = reading,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
            if (default != null) {
                tf.monochrome.android.ui.settings.crt.SliderReset(value, default, label) { onChange(default) }
            }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = scheme.onSurface,
                activeTrackColor = scheme.onSurface,
                inactiveTrackColor = scheme.onSurface.copy(alpha = SLIDER_TRACK_ALPHA),
                activeTickColor = scheme.surface.copy(alpha = SLIDER_TICK_ALPHA),
                inactiveTickColor = scheme.onSurface.copy(alpha = SLIDER_TICK_ALPHA),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = reading
                },
        )
    }
}

private const val SLIDER_TRACK_ALPHA = 0.18f
private const val SLIDER_TICK_ALPHA = 0.5f

private const val THUMB_MILLIS = 180
private val THUMB_INSET = 3.dp
