package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.input.KeyboardType
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
import tf.monochrome.android.ui.components.adjustableSemantics
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

    // Sweeps to a new value (a preset, a reset) rather than jumping; follows
    // the finger exactly while held.
    val shownFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (isTouching) snap() else tween(durationMillis = 220),
        label = "knobFraction"
    )
    // A parameter that runs either side of zero (pan, a gain in dB) lights its
    // arc out from zero, so "no change" reads as an empty arc.
    val bipolar = min < 0f && max > 0f
    val zeroFraction = if (bipolar) (-min / (max - min)) else 0f
    val defaultFraction = default?.let { ((it - min) / (max - min)).coerceIn(0f, 1f) }
    var typing by remember { mutableStateOf(false) }

    val cs = MaterialTheme.colorScheme
    val bodyLight = lerp(cs.surfaceContainerHighest, Color.White, 0.12f)
    val bodyDark = lerp(cs.surfaceContainerLowest, Color.Black, 0.35f)
    val trackColor = cs.onSurface.copy(alpha = 0.10f)
    val tickColor = cs.onSurface.copy(alpha = 0.18f)
    val markerColor = cs.onSurface.copy(alpha = 0.45f)
    val valueColor = cs.onSurface.copy(alpha = 0.85f)

    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Label (brighter when touching)
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = if (isTouching) FontWeight.Bold else FontWeight.Medium,
            color = if (isTouching) color else cs.onSurfaceVariant,
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
                .adjustableSemantics(
                    label = label,
                    value = value,
                    range = min..max,
                    stateText = { formatParamValue(it, ParamDef(label, min, max, it, unit, steps)) },
                    onValueChange = { onValueChange(snapValue(it, min, max, steps)) },
                )
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
            val center = Offset(cx, cy)
            val radius = size.minDimension / 2f - 8.dp.toPx()
            val strokeW = 3.5.dp.toPx()
            val shown = shownFraction

            // Ticks around the outside; the ones the value has passed are lit.
            val ticks = 11
            for (i in 0 until ticks) {
                val t = i / (ticks - 1f)
                val a = Math.toRadians((135.0 + t * 270.0)).toFloat()
                val lit = if (bipolar) (t - zeroFraction) * (shown - zeroFraction) >= 0f &&
                    kotlin.math.abs(t - zeroFraction) <= kotlin.math.abs(shown - zeroFraction)
                    else t <= shown
                val r0 = radius + 4.dp.toPx()
                val r1 = radius + (if (i == 0 || i == ticks - 1 || i == ticks / 2) 7.5.dp else 6.dp).toPx()
                drawLine(
                    color = if (lit) color.copy(alpha = 0.75f) else tickColor,
                    start = Offset(cx + r0 * cos(a), cy + r0 * sin(a)),
                    end = Offset(cx + r1 * cos(a), cy + r1 * sin(a)),
                    strokeWidth = 1.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Track, then the value arc — from the centre for a ± parameter.
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
            val from = if (bipolar) zeroFraction else 0f
            val startDeg = 135f + from * 270f
            val sweepDeg = (shown - from) * 270f
            if (kotlin.math.abs(sweepDeg) > 0.5f) {
                // Glow under the arc, brighter under the finger.
                drawArc(
                    color = color.copy(alpha = if (isTouching) 0.35f else 0.16f),
                    startAngle = startDeg,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeW * 2.6f, cap = StrokeCap.Round)
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(color.copy(alpha = 0.75f), color, lerp(color, Color.White, 0.25f), color.copy(alpha = 0.75f)),
                        center = center
                    ),
                    startAngle = startDeg,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
            }

            // Where double-tap takes it.
            defaultFraction?.let { d ->
                val a = Math.toRadians((135.0 + d * 270.0)).toFloat()
                drawCircle(
                    color = markerColor,
                    radius = 1.6.dp.toPx(),
                    center = Offset(cx + radius * cos(a), cy + radius * sin(a))
                )
            }

            // The body: a drop shadow, a lit dome, and a fine rim.
            val body = radius - 7.dp.toPx()
            drawCircle(
                color = Color.Black.copy(alpha = 0.30f),
                radius = body + 1.5.dp.toPx(),
                center = Offset(cx, cy + 2.dp.toPx())
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(bodyLight, bodyDark),
                    center = Offset(cx - body * 0.35f, cy - body * 0.45f),
                    radius = body * 1.6f
                ),
                radius = body,
                center = center
            )
            drawCircle(
                color = color.copy(alpha = if (isTouching) 0.22f else 0.08f),
                radius = body,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = body,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Pointer: a line to a lit dot near the edge.
            val angleRad = Math.toRadians((135.0 + shown * 270.0)).toFloat()
            val tip = Offset(cx + body * 0.72f * cos(angleRad), cy + body * 0.72f * sin(angleRad))
            drawLine(
                color = color.copy(alpha = 0.85f),
                start = Offset(cx + body * 0.22f * cos(angleRad), cy + body * 0.22f * sin(angleRad)),
                end = tip,
                strokeWidth = if (isTouching) 3.dp.toPx() else 2.4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(color = color.copy(alpha = 0.35f), radius = 4.5.dp.toPx(), center = tip)
            drawCircle(color = lerp(color, Color.White, 0.35f), radius = 2.dp.toPx(), center = tip)
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Value readout: tap it to type an exact value.
        Text(
            text = formatParamValue(value, ParamDef(label, min, max, value, unit, steps)),
            fontSize = if (isTouching) 11.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isTouching) color else valueColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "Type a value") { typing = true }
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }

    if (typing) {
        ValueEntryDialog(
            label = label,
            value = value,
            min = min,
            max = max,
            unit = unit,
            onDismiss = { typing = false },
            onConfirm = {
                onValueChange(snapValue(it.coerceIn(min, max), min, max, steps))
                typing = false
            }
        )
    }
}

/** Type an exact value for a knob; accepts "1.5k" for 1500. */
@Composable
private fun ValueEntryDialog(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(trimFloat(value)) }
    val parsed = parseEntry(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = parsed == null,
                suffix = if (unit.isNotEmpty()) ({ Text(unit) }) else null,
                supportingText = { Text("${trimFloat(min)} to ${trimFloat(max)}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun trimFloat(v: Float): String =
    if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e7f) v.toLong().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.')

/** "1.5k" → 1500, "-3" → -3; null if it is not a number. */
internal fun parseEntry(text: String): Float? {
    val t = text.trim().replace(',', '.')
    val k = t.endsWith("k", ignoreCase = true)
    val n = (if (k) t.dropLast(1) else t).trim().toFloatOrNull() ?: return null
    val v = if (k) n * 1000f else n
    return v.takeIf { it.isFinite() }
}
