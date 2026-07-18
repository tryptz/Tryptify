package tf.monochrome.android.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.eq.AutoEqEngine
import tf.monochrome.android.domain.model.ToneControls
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// The two gain "O" knobs are ~20% larger than the "o" cutoff/Q knobs, and sit on
// the outer edges — bass far left, treble far right — mirroring each other:
// "Ooo   ooO".
private val KNOB_BIG = 56.dp
private val KNOB_SMALL = 40.dp

/**
 * Collapsible bass/treble tone panel for the Audio tools sheet: a live frequency-
 * response curve of the two shelves plus their knobs (gain / cutoff / Q). These
 * shelves are layered AFTER the AutoEQ correction in the system-wide effect.
 */
@Composable
internal fun ToneControlsPanel(
    tone: ToneControls,
    accent: Color,
    onChange: (ToneControls) -> Unit,
    modifier: Modifier = Modifier,
    // White suits the dark audio-tools sheet; pass onSurface on themed screens.
    contentColor: Color = Color.White,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val d = ToneControls.DEFAULT
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Collapsible header with the tone on/off switch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tone",
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = tone.enabled,
                onCheckedChange = { onChange(tone.copy(enabled = it)) },
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse tone" else "Expand tone",
                tint = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            // Dim the controls while the stage is bypassed; still adjustable.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.alpha(if (tone.enabled) 1f else 0.45f),
            ) {
                ToneCurve(
                    tone = tone,
                    accent = accent,
                    baseline = contentColor,
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                )
                // Mirrored layout: bass big knob hard left, treble big knob hard
                // right, the small cutoff/Q knobs facing each other in the middle.
                // Double-tap any knob to reset it to its default.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Knob("Bass", "%+.0f dB".format(tone.bassGainDb), tone.bassGainDb,
                        ToneControls.GAIN_MIN..ToneControls.GAIN_MAX, accent, contentColor, KNOB_BIG,
                        d.bassGainDb) {
                        onChange(tone.copy(bassGainDb = snap(it, 0.5f)))
                    }
                    Knob("Freq", "${tone.bassFreq.roundToInt()} Hz", tone.bassFreq,
                        ToneControls.BASS_FREQ_MIN..ToneControls.BASS_FREQ_MAX, accent, contentColor, KNOB_SMALL,
                        d.bassFreq) {
                        onChange(tone.copy(bassFreq = it))
                    }
                    Knob("Q", "%.2f".format(tone.bassQ), tone.bassQ,
                        ToneControls.Q_MIN..ToneControls.Q_MAX, accent, contentColor, KNOB_SMALL,
                        d.bassQ) {
                        onChange(tone.copy(bassQ = it))
                    }
                    Knob("Q", "%.2f".format(tone.trebleQ), tone.trebleQ,
                        ToneControls.Q_MIN..ToneControls.Q_MAX, accent, contentColor, KNOB_SMALL,
                        d.trebleQ) {
                        onChange(tone.copy(trebleQ = it))
                    }
                    Knob("Freq", "${(tone.trebleFreq / 1000f).format1()} kHz", tone.trebleFreq,
                        ToneControls.TREBLE_FREQ_MIN..ToneControls.TREBLE_FREQ_MAX, accent, contentColor, KNOB_SMALL,
                        d.trebleFreq) {
                        onChange(tone.copy(trebleFreq = it))
                    }
                    Knob("Treble", "%+.0f dB".format(tone.trebleGainDb), tone.trebleGainDb,
                        ToneControls.GAIN_MIN..ToneControls.GAIN_MAX, accent, contentColor, KNOB_BIG,
                        d.trebleGainDb) {
                        onChange(tone.copy(trebleGainDb = snap(it, 0.5f)))
                    }
                }
            }
        }
    }
}

/** A live curve of the two shelves across 20 Hz–20 kHz (log x, ±12 dB y). */
@Composable
private fun ToneCurve(tone: ToneControls, accent: Color, baseline: Color, modifier: Modifier) {
    val bands = tone.toBands()
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        drawLine(
            color = baseline.copy(alpha = 0.14f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1f,
        )
        val steps = 110
        val path = Path()
        for (i in 0..steps) {
            val fx = i / steps.toFloat()
            val freq = 20f * 1000f.pow(fx) // 20..20000, log-spaced
            var db = 0f
            for (b in bands) db += AutoEqEngine.calculateBiquadResponse(freq, b)
            val y = midY - (db / 12f).coerceIn(-1f, 1f) * (midY * 0.9f)
            val x = fx * size.width
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A rotary knob: 270° sweep, vertical drag to change. [knobSize] sets its diameter. */
@Composable
private fun Knob(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    contentColor: Color,
    knobSize: Dp,
    resetValue: Float,
    onChange: (Float) -> Unit,
) {
    val span = range.endInclusive - range.start
    val curValue by rememberUpdatedState(value)
    val curOnChange by rememberUpdatedState(onChange)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(horizontal = 1.dp),
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                // Double-tap resets the knob to its default.
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { curOnChange(resetValue) })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        // ~150 px of vertical travel spans the whole range; up = up.
                        val next = (curValue - drag.y / 150f * span)
                            .coerceIn(range.start, range.endInclusive)
                        curOnChange(next)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(knobSize)) {
                val stroke = 4.dp.toPx()
                val radius = size.minDimension / 2f - stroke
                val center = Offset(size.width / 2f, size.height / 2f)
                val startAngle = 135f
                val sweep = 270f
                drawArc(
                    color = contentColor.copy(alpha = 0.15f),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                val frac = ((value - range.start) / span).coerceIn(0f, 1f)
                drawArc(
                    color = accent,
                    startAngle = startAngle,
                    sweepAngle = sweep * frac,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                val ang = Math.toRadians((startAngle + sweep * frac).toDouble())
                val px = center.x + (radius * cos(ang)).toFloat()
                val py = center.y + (radius * sin(ang)).toFloat()
                drawCircle(color = contentColor, radius = stroke * 0.7f, center = Offset(px, py))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
        Text(valueText, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

private fun snap(value: Float, step: Float): Float = (value / step).roundToInt() * step

private fun Float.format1(): String = "%.1f".format(this)
