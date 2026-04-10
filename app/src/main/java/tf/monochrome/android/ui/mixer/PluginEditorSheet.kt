package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── FL Studio Mobile color palette ─────────────────────────────────────
private object FLPluginColors {
    val bg = Color(0xFF1A1A2E)
    val headerBg = Color(0xFF16213E)
    val knobBg = Color(0xFF0F3460)
    val knobTrack = Color(0xFF2A2A4A)
    val knobAccent = Color(0xFF00D4AA)
    val knobOrange = Color(0xFFFF6B35)
    val knobBlue = Color(0xFF4A9EFF)
    val knobPurple = Color(0xFFBB86FC)
    val knobYellow = Color(0xFFFFD93D)
    val knobPink = Color(0xFFFF6B9D)
    val textPrimary = Color(0xFFE0E0E0)
    val textSecondary = Color(0xFF808090)
    val textValue = Color(0xFF00D4AA)
    val divider = Color(0xFF2A2A4A)
}

// Assign a color to each parameter index for visual variety
private fun knobColor(paramIndex: Int): Color = when (paramIndex % 6) {
    0 -> FLPluginColors.knobAccent
    1 -> FLPluginColors.knobOrange
    2 -> FLPluginColors.knobBlue
    3 -> FLPluginColors.knobPurple
    4 -> FLPluginColors.knobYellow
    5 -> FLPluginColors.knobPink
    else -> FLPluginColors.knobAccent
}

// Parameter metadata per snapin type
data class ParamDef(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = ""
)

internal fun getParamDefs(type: SnapinType?): List<ParamDef> = when (type) {
    SnapinType.GAIN -> listOf(
        ParamDef("Gain", -100f, 24f, 0f, "dB")
    )
    SnapinType.STEREO -> listOf(
        ParamDef("Mid", -24f, 24f, 0f, "dB"),
        ParamDef("Width", -24f, 24f, 0f, "dB"),
        ParamDef("Pan", -1f, 1f, 0f, "")
    )
    SnapinType.FILTER -> listOf(
        ParamDef("Type", 0f, 6f, 0f, ""),
        ParamDef("Cutoff", 20f, 20000f, 1000f, "Hz"),
        ParamDef("Q", 0.1f, 20f, 0.707f, ""),
        ParamDef("Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Slope", 0f, 3f, 0f, "x")
    )
    SnapinType.EQ_3BAND -> listOf(
        ParamDef("Lo Freq", 20f, 500f, 100f, "Hz"),
        ParamDef("Lo Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Lo Q", 0.1f, 10f, 0.7f, ""),
        ParamDef("Mid Freq", 200f, 8000f, 1000f, "Hz"),
        ParamDef("Mid Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Mid Q", 0.1f, 10f, 1f, ""),
        ParamDef("Hi Freq", 2000f, 20000f, 8000f, "Hz"),
        ParamDef("Hi Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Hi Q", 0.1f, 10f, 0.7f, "")
    )
    SnapinType.COMPRESSOR -> listOf(
        ParamDef("Attack", 0.1f, 300f, 10f, "ms"),
        ParamDef("Release", 1f, 3000f, 100f, "ms"),
        ParamDef("Ratio", 1f, 100f, 4f, ":1"),
        ParamDef("Thresh", -60f, 0f, -18f, "dB"),
        ParamDef("Knee", 0f, 24f, 6f, "dB"),
        ParamDef("Makeup", 0f, 40f, 0f, "dB"),
        ParamDef("Mode", 0f, 1f, 0f, ""),
        ParamDef("Look", 0f, 10f, 0f, "ms"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.LIMITER -> listOf(
        ParamDef("In Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Thresh", -24f, 0f, 0f, "dB"),
        ParamDef("Release", 1f, 1000f, 100f, "ms"),
        ParamDef("Look", 1f, 10f, 5f, "ms"),
        ParamDef("Out Gain", -24f, 24f, 0f, "dB")
    )
    SnapinType.GATE -> listOf(
        ParamDef("Attack", 0.01f, 100f, 0.1f, "ms"),
        ParamDef("Hold", 0f, 500f, 50f, "ms"),
        ParamDef("Release", 1f, 2000f, 100f, "ms"),
        ParamDef("Thresh", -80f, 0f, -30f, "dB"),
        ParamDef("Toler.", 0f, 24f, 6f, "dB"),
        ParamDef("Range", 0f, 80f, 80f, "dB"),
        ParamDef("Lookahead", 0f, 1f, 0f, ""),
        ParamDef("Flip", 0f, 1f, 0f, ""),
        ParamDef("SC", 0f, 1f, 0f, "")
    )
    SnapinType.DYNAMICS -> listOf(
        ParamDef("Lo Thr", -60f, 0f, -40f, "dB"),
        ParamDef("Lo Ratio", 0.5f, 4f, 1f, ":1"),
        ParamDef("Hi Thr", -60f, 0f, -12f, "dB"),
        ParamDef("Hi Ratio", 1f, 100f, 4f, ":1"),
        ParamDef("Attack", 0.1f, 300f, 10f, "ms"),
        ParamDef("Release", 1f, 3000f, 100f, "ms"),
        ParamDef("Knee", 0f, 24f, 6f, "dB"),
        ParamDef("In", -24f, 24f, 0f, "dB"),
        ParamDef("Out", -24f, 24f, 0f, "dB"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.COMPACTOR -> listOf(
        ParamDef("Attack", 0f, 50f, 5f, "ms"),
        ParamDef("Hold", 0f, 500f, 10f, "ms"),
        ParamDef("Release", 1f, 1000f, 100f, "ms"),
        ParamDef("Thresh", -60f, 0f, -12f, "dB"),
        ParamDef("Mode", 0f, 2f, 1f, ""),
        ParamDef("Range", 0f, 200f, 100f, "%"),
        ParamDef("Stereo", 0f, 100f, 0f, "%"),
        ParamDef("SC", 0f, 1f, 0f, "")
    )
    SnapinType.TRANSIENT_SHAPER -> listOf(
        ParamDef("Attack", -100f, 100f, 0f, "%"),
        ParamDef("Pump", 0f, 100f, 0f, "%"),
        ParamDef("Sustain", -100f, 100f, 0f, "%"),
        ParamDef("Speed", 0f, 100f, 50f, "%"),
        ParamDef("Clip", 0f, 1f, 0f, ""),
        ParamDef("SC", 0f, 1f, 0f, "")
    )
    SnapinType.DISTORTION -> listOf(
        ParamDef("Drive", 0f, 48f, 12f, "dB"),
        ParamDef("Type", 0f, 5f, 0f, ""),
        ParamDef("Tone", 200f, 20000f, 12000f, "Hz"),
        ParamDef("Bias", -1f, 1f, 0f, ""),
        ParamDef("Dynamics", 0f, 100f, 0f, "%"),
        ParamDef("Spread", 0f, 100f, 0f, "%"),
        ParamDef("Output", -24f, 0f, 0f, "dB"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.SHAPER -> listOf(
        ParamDef("Drive", 0f, 48f, 0f, "dB"),
        ParamDef("Mix", 0f, 100f, 100f, "%"),
        ParamDef("Overflow", 0f, 2f, 0f, ""),
        ParamDef("DC Filter", 0f, 1f, 1f, "")
    )
    SnapinType.CHORUS -> listOf(
        ParamDef("Delay", 1f, 40f, 7f, "ms"),
        ParamDef("Rate", 0.01f, 10f, 1f, "Hz"),
        ParamDef("Depth", 0f, 100f, 50f, "%"),
        ParamDef("Voices", 1f, 8f, 4f, ""),
        ParamDef("Spread", 0f, 100f, 50f, "%"),
        ParamDef("FB", 0f, 50f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.ENSEMBLE -> listOf(
        ParamDef("Voices", 2f, 8f, 4f, ""),
        ParamDef("Detune", 0f, 100f, 50f, "%"),
        ParamDef("Spread", 0f, 100f, 80f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%"),
        ParamDef("Motion", 0f, 2f, 0f, "")
    )
    SnapinType.FLANGER -> listOf(
        ParamDef("Delay", 0.1f, 10f, 1f, "ms"),
        ParamDef("Depth", 0f, 100f, 50f, "%"),
        ParamDef("Rate", 0.01f, 10f, 0.5f, "Hz"),
        ParamDef("FB", -95f, 95f, 30f, "%"),
        ParamDef("Stereo", 0f, 100f, 50f, "%"),
        ParamDef("Tone", 200f, 20000f, 12000f, "Hz"),
        ParamDef("Thru-0", 0f, 1f, 0f, ""),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.PHASER -> listOf(
        ParamDef("Stages", 2f, 12f, 6f, ""),
        ParamDef("Rate", 0.01f, 10f, 0.5f, "Hz"),
        ParamDef("Depth", 0f, 100f, 50f, "%"),
        ParamDef("Center", 200f, 10000f, 1000f, "Hz"),
        ParamDef("FB", -90f, 90f, 30f, "%"),
        ParamDef("Spread", 0f, 360f, 90f, "\u00B0"),
        ParamDef("Stereo", 0f, 100f, 50f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.DELAY -> listOf(
        ParamDef("Time", 1f, 2000f, 250f, "ms"),
        ParamDef("FB", 0f, 100f, 30f, "%"),
        ParamDef("PP", 0f, 1f, 0f, ""),
        ParamDef("Pan", -100f, 100f, 0f, ""),
        ParamDef("Duck", 0f, 100f, 0f, "%"),
        ParamDef("FB Lo", 20f, 2000f, 80f, "Hz"),
        ParamDef("FB Hi", 500f, 20000f, 12000f, "Hz"),
        ParamDef("Mod", 0f, 100f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.REVERB -> listOf(
        ParamDef("Pre-Dly", 0f, 500f, 20f, "ms"),
        ParamDef("Decay", 0.1f, 30f, 2f, "s"),
        ParamDef("Size", 0f, 100f, 50f, "%"),
        ParamDef("Damp", 0f, 100f, 50f, "%"),
        ParamDef("Diffuse", 0f, 100f, 70f, "%"),
        ParamDef("Mod Rate", 0.05f, 5f, 0.8f, "Hz"),
        ParamDef("Mod Dep", 0f, 100f, 20f, "%"),
        ParamDef("Tone", 500f, 20000f, 8000f, "Hz"),
        ParamDef("Lo Cut", 20f, 500f, 80f, "Hz"),
        ParamDef("Early", 0f, 100f, 30f, "%"),
        ParamDef("Width", 0f, 100f, 100f, "%"),
        ParamDef("Mix", 0f, 100f, 30f, "%")
    )
    SnapinType.BITCRUSH -> listOf(
        ParamDef("Rate", 200f, 48000f, 48000f, "Hz"),
        ParamDef("Bits", 1f, 24f, 24f, ""),
        ParamDef("ADC", 0f, 100f, 100f, "%"),
        ParamDef("DAC", 0f, 100f, 100f, "%"),
        ParamDef("Dither", 0f, 100f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.COMB_FILTER -> listOf(
        ParamDef("Cutoff", 20f, 20000f, 440f, "Hz"),
        ParamDef("Mix", 0f, 100f, 50f, "%"),
        ParamDef("Polar.", 0f, 1f, 0f, ""),
        ParamDef("Stereo", 0f, 1f, 0f, "")
    )
    SnapinType.CHANNEL_MIXER -> listOf(
        ParamDef("L\u2192L", -1f, 1f, 1f, ""),
        ParamDef("R\u2192L", -1f, 1f, 0f, ""),
        ParamDef("L\u2192R", -1f, 1f, 0f, ""),
        ParamDef("R\u2192R", -1f, 1f, 1f, "")
    )
    SnapinType.FORMANT_FILTER -> listOf(
        ParamDef("Vowel X", 0f, 1f, 0.5f, ""),
        ParamDef("Vowel Y", 0f, 1f, 0.5f, ""),
        ParamDef("Q", 0.5f, 20f, 5f, ""),
        ParamDef("Lows", 0f, 100f, 0f, "%"),
        ParamDef("Highs", 0f, 100f, 0f, "%")
    )
    SnapinType.FREQUENCY_SHIFTER -> listOf(
        ParamDef("Shift", -5000f, 5000f, 0f, "Hz")
    )
    SnapinType.HAAS -> listOf(
        ParamDef("Channel", 0f, 1f, 0f, ""),
        ParamDef("Delay", 0f, 30f, 10f, "ms")
    )
    SnapinType.LADDER_FILTER -> listOf(
        ParamDef("Cutoff", 20f, 20000f, 1000f, "Hz"),
        ParamDef("Reso", 0f, 100f, 0f, "%"),
        ParamDef("Topo", 0f, 1f, 0f, ""),
        ParamDef("Sat", 0f, 1f, 0f, ""),
        ParamDef("Drive", 0f, 48f, 0f, "dB"),
        ParamDef("Bias", -1f, 1f, 0f, "")
    )
    SnapinType.NONLINEAR_FILTER -> listOf(
        ParamDef("Type", 0f, 3f, 0f, ""),
        ParamDef("Cutoff", 20f, 20000f, 1000f, "Hz"),
        ParamDef("Q", 0.1f, 20f, 0.707f, ""),
        ParamDef("Drive", 0f, 48f, 0f, "dB"),
        ParamDef("Mode", 0f, 4f, 0f, "")
    )
    SnapinType.PHASE_DISTORTION -> listOf(
        ParamDef("Drive", 0f, 100f, 30f, "%"),
        ParamDef("Norm", 0f, 1f, 0f, ""),
        ParamDef("Tone", 0f, 100f, 100f, "%"),
        ParamDef("Bias", -3.14f, 3.14f, 0f, "rad"),
        ParamDef("Spread", 0f, 100f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.PITCH_SHIFTER -> listOf(
        ParamDef("Pitch", -24f, 24f, 0f, "st"),
        ParamDef("Jitter", 0f, 100f, 0f, "%"),
        ParamDef("Grain", 10f, 200f, 50f, "ms"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.RESONATOR -> listOf(
        ParamDef("Pitch", 0f, 127f, 69f, ""),
        ParamDef("Decay", 0f, 100f, 50f, "%"),
        ParamDef("Intens.", 0f, 100f, 50f, "%"),
        ParamDef("Timbre", 0f, 1f, 0f, ""),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.REVERSER -> listOf(
        ParamDef("Time", 50f, 2000f, 250f, "ms"),
        ParamDef("Sync", 0f, 1f, 0f, ""),
        ParamDef("X-Fade", 1f, 50f, 10f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.RING_MOD -> listOf(
        ParamDef("Freq", 1f, 5000f, 440f, "Hz"),
        ParamDef("Bias", 0f, 100f, 0f, "%"),
        ParamDef("Rect", -100f, 100f, 0f, "%"),
        ParamDef("Spread", 0f, 100f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.TAPE_STOP -> listOf(
        ParamDef("Play", 0f, 1f, 1f, ""),
        ParamDef("Stop", 50f, 5000f, 500f, "ms"),
        ParamDef("Start", 50f, 5000f, 500f, "ms"),
        ParamDef("Curve", 0f, 100f, 50f, "%")
    )
    SnapinType.TRANCE_GATE -> listOf(
        ParamDef("Pattern", 0f, 7f, 0f, ""),
        ParamDef("Length", 1f, 32f, 16f, ""),
        ParamDef("Attack", 0.1f, 100f, 1f, "ms"),
        ParamDef("Decay", 0.1f, 500f, 50f, "ms"),
        ParamDef("Sustain", 0f, 100f, 100f, "%"),
        ParamDef("Release", 0.1f, 500f, 10f, "ms"),
        ParamDef("Mix", 0f, 100f, 100f, "%"),
        ParamDef("Res", 0f, 3f, 2f, "")
    )
    else -> emptyList()
}

// ── FL Studio Mobile-style Plugin Editor ───────────────────────────────

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
                            val color = knobColor(paramIndex)

                            FLKnobControl(
                                label = def.name,
                                value = currentValue,
                                min = def.min,
                                max = def.max,
                                unit = def.unit,
                                color = color,
                                onValueChange = { onParameterChange(busIndex, slotIndex, paramIndex, it) },
                                modifier = Modifier.weight(1f)
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

// ── FL Studio Mobile-style rotary knob with touch-optimized control ─────
//
// Touch interactions:
//   - Vertical drag: coarse adjustment (drag up = increase, down = decrease)
//   - Horizontal drag far from knob: fine-tune mode (4x less sensitive)
//   - Rotary gesture: drag around the knob in a circle for natural rotation
//   - Double-tap: reset to default value
//   - Haptic feedback at min, max, default, and center detent points

@Composable
private fun FLKnobControl(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    color: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val default = remember(min, max) { (min + max) / 2f }  // midpoint as default
    var isTouching by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // Track previous fraction for detent haptics
    val prevFraction = remember { mutableFloatStateOf(fraction) }

    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Label (brighter when touching)
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = if (isTouching) FontWeight.Bold else FontWeight.Medium,
            color = if (isTouching) color else FLPluginColors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Knob canvas with advanced touch handling
        Canvas(
            modifier = Modifier
                .size(64.dp)
                .pointerInput(min, max) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f

                    awaitEachGesture {
                        val down = awaitFirstDown()
                        isTouching = true
                        var lastPos = down.position
                        var totalVertical = 0f
                        var lastAngle: Float? = null

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                isTouching = false
                                break
                            }

                            val pos = change.position
                            val dx = pos.x - lastPos.x
                            val dy = pos.y - lastPos.y

                            // Distance from knob center determines mode
                            val distFromCenter = kotlin.math.sqrt(
                                (pos.x - cx) * (pos.x - cx) + (pos.y - cy) * (pos.y - cy)
                            )

                            val range = max - min
                            val currentVal = value

                            if (distFromCenter > cx * 1.5f) {
                                // ── Fine-tune mode: far from knob, horizontal drag ──
                                val sensitivity = range / 800f
                                val newVal = (currentVal + dx * sensitivity).coerceIn(min, max)
                                onValueChange(newVal)
                            } else if (distFromCenter > cx * 0.3f) {
                                // ── Rotary mode: circular drag around knob ──
                                val angle = kotlin.math.atan2(pos.y - cy, pos.x - cx)
                                if (lastAngle != null) {
                                    var delta = angle - lastAngle!!
                                    // Wrap around -PI/PI boundary
                                    if (delta > Math.PI.toFloat()) delta -= 2f * Math.PI.toFloat()
                                    if (delta < -Math.PI.toFloat()) delta += 2f * Math.PI.toFloat()
                                    // Map rotation to value change (full circle = full range)
                                    val newVal = (currentVal + delta / (1.5f * Math.PI.toFloat()) * range)
                                        .coerceIn(min, max)
                                    onValueChange(newVal)
                                }
                                lastAngle = angle
                            } else {
                                // ── Vertical drag mode: standard coarse control ──
                                val sensitivity = range / 250f
                                val newVal = (currentVal - dy * sensitivity).coerceIn(min, max)
                                onValueChange(newVal)
                                lastAngle = null
                            }

                            // Haptic detents at min, max, center, default
                            val newFrac = ((value - min) / range).coerceIn(0f, 1f)
                            val oldFrac = prevFraction.floatValue
                            val detents = listOf(0f, 0.5f, 1f)
                            for (d in detents) {
                                if ((oldFrac < d && newFrac >= d) || (oldFrac > d && newFrac <= d)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    break
                                }
                            }
                            prevFraction.floatValue = newFrac

                            lastPos = pos
                            change.consume()
                        }
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension / 2f - 6.dp.toPx()
            val strokeW = 3.5.dp.toPx()

            // Touch glow ring when active
            if (isTouching) {
                drawCircle(
                    color = color.copy(alpha = 0.12f),
                    radius = radius + 8.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }

            // Background track arc (270 degrees)
            drawArc(
                color = FLPluginColors.knobTrack,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )

            // Active arc
            val sweepDeg = fraction * 270f
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = sweepDeg,
                useCenter = false,
                style = Stroke(width = if (isTouching) strokeW + 1.dp.toPx() else strokeW, cap = StrokeCap.Round)
            )

            // Center filled circle (knob body)
            val innerRadius = radius - 6.dp.toPx()
            drawCircle(
                color = FLPluginColors.knobBg,
                radius = innerRadius,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = color.copy(alpha = if (isTouching) 0.25f else 0.12f),
                radius = innerRadius,
                center = Offset(cx, cy)
            )

            // Indicator line
            val angleRad = Math.toRadians((135.0 + sweepDeg).toDouble()).toFloat()
            val lineInner = innerRadius * 0.25f
            val lineOuter = innerRadius * 0.9f
            drawLine(
                color = color,
                start = Offset(
                    cx + lineInner * cos(angleRad),
                    cy + lineInner * sin(angleRad)
                ),
                end = Offset(
                    cx + lineOuter * cos(angleRad),
                    cy + lineOuter * sin(angleRad)
                ),
                strokeWidth = if (isTouching) 3.dp.toPx() else 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Center dot
            drawCircle(
                color = color.copy(alpha = 0.6f),
                radius = 2.dp.toPx(),
                center = Offset(cx, cy)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Value readout (highlighted when touching)
        Text(
            text = formatValue(value, ParamDef(label, min, max, value, unit)),
            fontSize = if (isTouching) 11.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isTouching) color else FLPluginColors.textValue,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private fun formatValue(value: Float, def: ParamDef): String {
    val formatted = if (def.max - def.min > 100) {
        "%.0f".format(value)
    } else if (def.max - def.min > 10) {
        "%.1f".format(value)
    } else {
        "%.2f".format(value)
    }
    return if (def.unit.isNotEmpty()) "$formatted ${def.unit}" else formatted
}
