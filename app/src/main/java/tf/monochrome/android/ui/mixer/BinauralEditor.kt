package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.ui.theme.MonoDimens

// ── Binaural-specific colors ───────────────────────────────────────────────
// Neutrals resolve from MaterialTheme so the sheet works on light themes too;
// the cyan/purple accents are deliberate plugin branding and stay fixed.
private data class BinauralPalette(
    val bg: Color,
    val headerBg: Color,
    val surfaceAlt: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentCyan: Color = Color(0xFF00D4D4),
    val accentPurple: Color = Color(0xFFB084CC),
    val switchEnabled: Color = Color(0xFF00D4D4),
    val switchDisabled: Color = Color(0xFF4A5568)
)

@Composable
private fun rememberBinauralPalette(): BinauralPalette {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    return BinauralPalette(
        bg = cs.surface,
        headerBg = cs.surfaceVariant,
        surfaceAlt = cs.surfaceContainerHigh,
        divider = cs.outlineVariant,
        textPrimary = cs.onSurface,
        textSecondary = cs.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinauralEditorSheet(
    plugin: PluginInstance,
    busIndex: Int,
    slotIndex: Int,
    onParameterChange: (busIndex: Int, slotIndex: Int, paramIndex: Int, value: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = rememberBinauralPalette()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Extract parameter values
    val mode = plugin.parameters[0] ?: 0f
    val crossfeedEnabled = (plugin.parameters[1] ?: 0f) > 0.5f
    val crossfeedLevel = plugin.parameters[2] ?: 50f
    val hrtfPreset = plugin.parameters[3] ?: 0f
    val stereoWidth = plugin.parameters[4] ?: 1f
    val widthAmount = plugin.parameters[5] ?: 1.1f
    var xfEnabled by remember { mutableStateOf(crossfeedEnabled) }
    var widthEnabled by remember { mutableStateOf(stereoWidth > 0.5f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.headerBg)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column {
                    Text(
                        text = "Binaural / Spatial DSP",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.accentCyan,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Multichannel HRTF rendering for Atmos & 3D Audio, crossfeed for stereo",
                        fontSize = 11.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Mode Selection ─────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Mode: Stereo",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Select output format for spatial processing",
                    fontSize = 10.sp,
                    color = palette.textSecondary.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Auto-enable Toggle ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-enable for Spatial Audio",
                        fontSize = 13.sp,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Automatically activate when Atmos or 3D content is detected",
                        fontSize = 10.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.padding(8.dp))
                var autoEnable by remember { mutableStateOf(mode > 0.5f) }
                Switch(
                    checked = autoEnable,
                    onCheckedChange = { newValue ->
                        autoEnable = newValue
                        onParameterChange(busIndex, slotIndex, 0, if (newValue) 1f else 0f)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.switchEnabled,
                        checkedTrackColor = palette.switchEnabled.copy(alpha = 0.3f),
                        uncheckedThumbColor = palette.switchDisabled,
                        uncheckedTrackColor = palette.switchDisabled.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Divider ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.divider)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Crossfeed Toggle ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Crossfeed",
                        fontSize = 13.sp,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Simulate speaker presentation on headphones",
                        fontSize = 10.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.padding(8.dp))
                Switch(
                    checked = xfEnabled,
                    onCheckedChange = { newValue ->
                        xfEnabled = newValue
                        onParameterChange(busIndex, slotIndex, 1, if (newValue) 1f else 0f)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.switchEnabled,
                        checkedTrackColor = palette.switchEnabled.copy(alpha = 0.3f),
                        uncheckedThumbColor = palette.switchDisabled,
                        uncheckedTrackColor = palette.switchDisabled.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Crossfeed Level Slider ─────────────────────────────────────
            if (xfEnabled) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Crossfeed Level",
                            fontSize = 12.sp,
                            color = palette.textSecondary
                        )
                        Text(
                            text = "${crossfeedLevel.toInt()}%",
                            fontSize = 12.sp,
                            color = palette.accentCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalSlider(
                        value = crossfeedLevel,
                        onValueChange = { onParameterChange(busIndex, slotIndex, 2, it) },
                        min = 0f,
                        max = 100f,
                        color = palette.accentCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Divider ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.divider)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── HRTF Preset Selector ───────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "HRTF Preset",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Virtual speaker angle for multichannel rendering",
                    fontSize = 10.sp,
                    color = palette.textSecondary.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                val hrtfOptions = listOf("Studio (±30°)", "Warm (±45°)", "Bright (±60°)", "Close (±20°)", "Far (±90°)", "Custom")
                val selectedIdx = hrtfPreset.toInt().coerceIn(0, hrtfOptions.size - 1)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    hrtfOptions.forEachIndexed { idx, label ->
                        PresetButton(
                            label = label,
                            isSelected = idx == selectedIdx,
                            onClick = { onParameterChange(busIndex, slotIndex, 3, idx.toFloat()) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Divider ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.divider)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Stereo Width Toggle ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stereo Width",
                        fontSize = 13.sp,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Adjust spatial width (0 = mono, 1 = neutral, 2 = wide)",
                        fontSize = 10.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.padding(8.dp))
                Switch(
                    checked = widthEnabled,
                    onCheckedChange = { newValue ->
                        widthEnabled = newValue
                        onParameterChange(busIndex, slotIndex, 4, if (newValue) 1f else 0f)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.accentPurple,
                        checkedTrackColor = palette.accentPurple.copy(alpha = 0.3f),
                        uncheckedThumbColor = palette.switchDisabled,
                        uncheckedTrackColor = palette.switchDisabled.copy(alpha = 0.3f)
                    )
                )
            }

            // ── Width Amount Slider ────────────────────────────────────────
            if (widthEnabled) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Width Amount",
                            fontSize = 12.sp,
                            color = palette.textSecondary
                        )
                        Text(
                            text = "%.2f".format(widthAmount),
                            fontSize = 12.sp,
                            color = palette.accentPurple,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalSlider(
                        value = widthAmount,
                        onValueChange = { onParameterChange(busIndex, slotIndex, 5, it) },
                        min = 0f,
                        max = 2f,
                        color = palette.accentPurple
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberBinauralPalette()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isSelected) palette.accentCyan.copy(alpha = 0.25f)
                else palette.surfaceAlt
            )
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) palette.accentCyan else palette.textSecondary
        )
    }
}

@Composable
private fun HorizontalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val palette = rememberBinauralPalette()
    val fraction = (value - min) / (max - min)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.divider)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
    }
}
