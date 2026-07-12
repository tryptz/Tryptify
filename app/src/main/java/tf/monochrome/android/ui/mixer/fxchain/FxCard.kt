package tf.monochrome.android.ui.mixer.fxchain

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.mixer.FLKnobControl
import tf.monochrome.android.ui.mixer.ParamDef
import tf.monochrome.android.ui.mixer.getParamDefs
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * One Serum-style stackable effect module in the FX chain.
 *
 * The base surface is `liquidGlass` over `MaterialTheme` colours so it tracks
 * the OS/app theme; the category [accent] appears only as the left stripe, the
 * tinted title, and the active bypass glow.
 */
@Composable
fun FxCard(
    position: Int,
    plugin: PluginInstance,
    accent: Color,
    expanded: Boolean,
    dragging: Boolean,
    dragHandle: Modifier,
    onToggleExpand: () -> Unit,
    onBypass: () -> Unit,
    onRemove: () -> Unit,
    onDryWet: (Float) -> Unit,
    onParam: (paramIndex: Int, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bypassed = plugin.bypassed
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "fxCardChevron"
    )
    val titleColor = if (bypassed) MaterialTheme.colorScheme.onSurfaceVariant else accent

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MonoDimens.shapeMd)
            .liquidGlass(
                shape = MonoDimens.shapeMd,
                tintAlpha = if (dragging) 0.28f else if (expanded) 0.20f else 0.12f,
                borderAlpha = if (dragging) MonoDimens.glassBorderAlpha * 2.5f
                              else MonoDimens.glassBorderAlpha
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category accent stripe (left edge)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(topStart = MonoDimens.radiusMd, bottomStart = MonoDimens.radiusMd))
                    .background(accent.copy(alpha = if (bypassed) 0.3f else 1f))
            )

            // Drag handle
            Box(modifier = dragHandle.padding(horizontal = 4.dp)) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Position number chip
            Text(
                text = "$position",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.width(16.dp)
            )

            // Title (tap toggles expand)
            Text(
                text = plugin.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpand)
                    .padding(vertical = 8.dp)
            )

            // Bypass power button
            IconButton(onClick = onBypass, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = if (bypassed) "Enable" else "Bypass",
                    tint = if (bypassed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                           else accent,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Expand chevron
            IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation)
                )
            }

            // Remove
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded && !dragging) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MonoDimens.spacingSm, vertical = MonoDimens.spacingSm),
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)
            ) {
                val defs = getParamDefs(plugin.type)
                if (plugin.type == SnapinType.EQ_10BAND) {
                    Eq10BandKnobs(defs, plugin, accent, onParam)
                } else {
                    KnobGrid(defs, plugin, accent, onParam)
                }

                DryWetRow(plugin.dryWet, accent, onDryWet)
            }
        }
    }
}

/** Generic 4-column knob grid used by every effect except the 10-band EQ. */
@Composable
private fun KnobGrid(
    defs: List<ParamDef>,
    plugin: PluginInstance,
    accent: Color,
    onParam: (Int, Float) -> Unit,
) {
    val columns = 4
    val rows = (defs.size + columns - 1) / columns
    for (row in 0 until rows) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (col in 0 until columns) {
                val paramIndex = row * columns + col
                if (paramIndex < defs.size) {
                    val def = defs[paramIndex]
                    FLKnobControl(
                        label = def.name,
                        value = plugin.parameters[paramIndex] ?: def.default,
                        min = def.min,
                        max = def.max,
                        unit = def.unit,
                        color = accent,
                        onValueChange = { onParam(paramIndex, it) },
                        modifier = Modifier.weight(1f),
                        steps = def.steps
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 10-band EQ layout: preamp on its own row, then one row per band grouping its
 * 5 params (Freq, Gain, Q, Type, On) under a "Band n" header.
 */
@Composable
private fun Eq10BandKnobs(
    defs: List<ParamDef>,
    plugin: PluginInstance,
    accent: Color,
    onParam: (Int, Float) -> Unit,
) {
    // Param 0 = preamp; then 5 params per band.
    if (defs.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val def = defs[0]
            FLKnobControl(
                label = def.name,
                value = plugin.parameters[0] ?: def.default,
                min = def.min, max = def.max, unit = def.unit,
                color = accent,
                onValueChange = { onParam(0, it) },
                modifier = Modifier.weight(1f),
                steps = def.steps
            )
            Spacer(modifier = Modifier.weight(3f))
        }
    }

    val bandCount = (defs.size - 1) / 5
    for (band in 0 until bandCount) {
        val base = 1 + band * 5
        Text(
            text = "Band ${band + 1}",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (offset in 0 until 5) {
                val paramIndex = base + offset
                val def = defs[paramIndex]
                FLKnobControl(
                    label = def.name.substringAfter(' '),  // drop the "Bn " prefix
                    value = plugin.parameters[paramIndex] ?: def.default,
                    min = def.min, max = def.max, unit = def.unit,
                    color = accent,
                    onValueChange = { onParam(paramIndex, it) },
                    modifier = Modifier.weight(1f),
                    steps = def.steps
                )
            }
        }
    }
}

@Composable
private fun DryWetRow(
    dryWet: Float,
    accent: Color,
    onDryWet: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "MIX",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = dryWet,
            onValueChange = onDryWet,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        )
        Text(
            text = "${(dryWet * 100).toInt()}%",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.width(34.dp)
        )
    }
}
