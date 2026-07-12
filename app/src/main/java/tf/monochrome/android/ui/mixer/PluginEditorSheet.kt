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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.dsp.model.PluginInstance

// ── FL Studio Mobile-style Plugin Editor ───────────────────────────────
//
// Bottom-sheet editor showing a 3-column grid of FLKnobControl rotary knobs
// for the selected plugin. Shared knob + palette + parameter metadata live in
// FLKnob.kt and ParamDefs.kt.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginEditorSheet(
    plugin: PluginInstance,
    busIndex: Int,
    slotIndex: Int,
    onParameterChange: (busIndex: Int, slotIndex: Int, paramIndex: Int, value: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val paramDefs = getParamDefs(plugin.type)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FLPluginColors.bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FLPluginColors.headerBg)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = plugin.displayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = FLPluginColors.knobAccent,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Knob grid (3 columns) ──────────────────────────────────
            val columns = 3
            val rows = (paramDefs.size + columns - 1) / columns

            for (row in 0 until rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (col in 0 until columns) {
                        val paramIndex = row * columns + col
                        if (paramIndex < paramDefs.size) {
                            val def = paramDefs[paramIndex]
                            val currentValue = plugin.parameters[paramIndex] ?: def.default

                            FLKnobControl(
                                label = def.name,
                                value = currentValue,
                                min = def.min,
                                max = def.max,
                                unit = def.unit,
                                color = knobColor(paramIndex),
                                onValueChange = { onParameterChange(busIndex, slotIndex, paramIndex, it) },
                                modifier = Modifier.weight(1f),
                                steps = def.steps
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
