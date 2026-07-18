package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.material3.Text
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

// ── FL Studio Mobile color palette (shared by the plugin editor + FX chain) ──
internal object FLPluginColors {
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
internal fun knobColor(paramIndex: Int): Color = when (paramIndex % 6) {
    0 -> FLPluginColors.knobAccent
    1 -> FLPluginColors.knobOrange
    2 -> FLPluginColors.knobBlue
    3 -> FLPluginColors.knobPurple
    4 -> FLPluginColors.knobYellow
    5 -> FLPluginColors.knobPink
    else -> FLPluginColors.knobAccent
}

/** Snap [raw] to one of [steps]+1 evenly-spaced stops across [min]..[max]. */
private fun snapValue(raw: Float, min: Float, max: Float, steps: Int?): Float {
    if (steps == null || steps <= 0 || max <= min) return raw
    val frac = ((raw - min) / (max - min)).coerceIn(0f, 1f)
    return min + (round(frac * steps) / steps) * (max - min)
}

// ── FL Studio Mobile-style rotary knob with touch-optimized control ─────
//
// Touch interactions:
//   - Vertical drag: coarse adjustment (drag up = increase, down = decrease)
//   - Horizontal drag far from knob: fine-tune mode (4x less sensitive)
//   - Rotary gesture: drag around the knob in a circle for natural rotation
//   - Double-tap: reset to default value
//   - Haptic feedback at min, max, default, and center detent points
//
// When [steps] is non-null the emitted value snaps to discrete stops (for
// enum-like params such as Type / Mode / On), while the internal accumulator
// stays smooth so the gesture still feels continuous.

@Composable
internal fun FLKnobControl(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    color: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int? = null,
    default: Float? = null
) {
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    var isTouching by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // The pointerInput gesture coroutine is keyed on (min, max) so it is not
    // restarted on every value change. Read the latest value / callback through
    // updated-state holders to avoid capturing a stale `value` in the closure,
    // which would make the knob snap back and appear frozen while dragging.
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestDefault by rememberUpdatedState(default)

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
                // Double-tap resets to the parameter default. Kept in its own
                // detector so it composes with — and yields to — the drag
                // gesture below: a real drag consumes movement, which cancels
                // this tap detector before it can fire.
                .pointerInput(min, max, steps) {
                    detectTapGestures(
                        onDoubleTap = {
                            val d = latestDefault ?: return@detectTapGestures
                            latestOnValueChange(snapValue(d, min, max, steps))
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                }
                .pointerInput(min, max) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val touchSlop = viewConfiguration.touchSlop

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        var lastPos = downPos
                        var lastAngle: Float? = null
                        val range = max - min
                        // Seed a running accumulator from the live value at the
                        // start of the gesture, then drive the knob off it. This
                        // keeps each move relative to the actual current value
                        // instead of a stale closure-captured one.
                        var current = latestValue
                        // Don't grab the pointer until the finger has travelled
                        // past touch slop: below it we leave events unconsumed so
                        // a parent scroll (the FX-chain list) can claim the drag
                        // instead of the knob swallowing every touch.
                        var dragging = false
                        // Mode is locked ONCE the moment slop is crossed, from the
                        // finger's initial landing point — so a single gesture no
                        // longer flips between rotary / vertical / fine mid-drag.
                        var mode = -1 // 0 = fine-tune, 1 = rotary, 2 = vertical

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break

                            val pos = change.position

                            if (!dragging) {
                                val distFromDown = kotlin.math.sqrt(
                                    (pos.x - downPos.x) * (pos.x - downPos.x) +
                                        (pos.y - downPos.y) * (pos.y - downPos.y)
                                )
                                if (distFromDown < touchSlop) continue
                                dragging = true
                                isTouching = true
                                val distFromCenter = kotlin.math.sqrt(
                                    (downPos.x - cx) * (downPos.x - cx) +
                                        (downPos.y - cy) * (downPos.y - cy)
                                )
                                mode = when {
                                    distFromCenter > cx * 1.5f -> 0
                                    distFromCenter > cx * 0.3f -> 1
                                    else -> 2
                                }
                                lastAngle = if (mode == 1) {
                                    kotlin.math.atan2(pos.y - cy, pos.x - cx)
                                } else null
                                lastPos = pos
                                change.consume()
                                continue
                            }

                            val dx = pos.x - lastPos.x
                            val dy = pos.y - lastPos.y
                            val oldFrac = ((current - min) / range).coerceIn(0f, 1f)

                            when (mode) {
                                0 -> {
                                    // ── Fine-tune: far from knob, horizontal drag ──
                                    val sensitivity = range / 800f
                                    current = (current + dx * sensitivity).coerceIn(min, max)
                                }
                                1 -> {
                                    // ── Rotary: circular drag around knob ──
                                    val angle = kotlin.math.atan2(pos.y - cy, pos.x - cx)
                                    val prev = lastAngle
                                    if (prev != null) {
                                        var delta = angle - prev
                                        // Wrap around -PI/PI boundary
                                        if (delta > Math.PI.toFloat()) delta -= 2f * Math.PI.toFloat()
                                        if (delta < -Math.PI.toFloat()) delta += 2f * Math.PI.toFloat()
                                        // Map rotation to value change (full circle = full range)
                                        current = (current + delta / (1.5f * Math.PI.toFloat()) * range)
                                            .coerceIn(min, max)
                                    }
                                    lastAngle = angle
                                }
                                else -> {
                                    // ── Vertical: standard coarse control ──
                                    val sensitivity = range / 250f
                                    current = (current - dy * sensitivity).coerceIn(min, max)
                                }
                            }

                            latestOnValueChange(snapValue(current, min, max, steps))

                            // Haptic detents at min, max, center, default
                            val newFrac = ((current - min) / range).coerceIn(0f, 1f)
                            val detents = listOf(0f, 0.5f, 1f)
                            for (d in detents) {
                                if ((oldFrac < d && newFrac >= d) || (oldFrac > d && newFrac <= d)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    break
                                }
                            }

                            lastPos = pos
                            change.consume()
                        }
                        isTouching = false
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
            text = formatParamValue(value, ParamDef(label, min, max, value, unit, steps)),
            fontSize = if (isTouching) 11.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isTouching) color else FLPluginColors.textValue,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
