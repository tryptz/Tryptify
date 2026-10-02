package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * A spring-loaded BPM nudge — a DJ's pitch bend, laid out like the stepper
 * above it: − on the left, + on the right.
 *
 * Push the thumb off centre and the tempo keeps moving for as long as it is
 * held there, faster the further it is pushed (the rate grows with the square
 * of the push, so a light touch drifts a fraction of a BPM per second and a
 * full push sweeps [MAX_RATE] a second). Let go and it springs back to centre;
 * the tempo stays where it got to.
 *
 * The readout follows every frame, but the audio is told at most every
 * [APPLY_EVERY_NANOS] and only when the tempo moved — each speed change resets
 * the player's playback parameters, and doing that 60 times a second is what
 * would make the audio stutter.
 */
@Composable
fun BpmNudge(
    playedBpm: Float,
    accent: Color,
    onBpmChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumb = remember { Animatable(0f) }          // -1..1, 0 at rest
    var held by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    // The tempo being dragged, ahead of what the player has been told.
    var liveBpm by remember { mutableFloatStateOf(playedBpm) }
    if (!held) liveBpm = playedBpm
    val apply by rememberUpdatedState(onBpmChange)
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Where the grip ridges are, scrolled by the bend the way a platter's
    // edge moves under the hand.
    var ridgePhase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(held) {
        if (!held) return@LaunchedEffect
        var last = 0L
        var lastApplied = 0L
        var appliedBpm = liveBpm
        // The platter has mass: the rate eases toward what the push asks for
        // rather than jumping to it.
        var rate = 0f
        var lastWhole = kotlin.math.floor(liveBpm)
        while (isActive && held) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val dt = (now - last) / 1_000_000_000f
                val x = thumb.value
                val target = sign(x) * x * x * MAX_RATE
                rate += (target - rate) * (dt * PLATTER_RESPONSE).coerceAtMost(1f)
                liveBpm = (liveBpm + rate * dt).coerceIn(MIN_BPM, MAX_BPM)
                ridgePhase += rate * dt * RIDGES_PER_BPM
                // A detent click on every whole BPM crossed.
                val whole = kotlin.math.floor(liveBpm)
                if (whole != lastWhole) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    lastWhole = whole
                }
            }
            last = now
            if (now - lastApplied >= APPLY_EVERY_NANOS && abs(liveBpm - appliedBpm) >= 0.05f) {
                apply(liveBpm)
                appliedBpm = liveBpm
                lastApplied = now
            }
        }
        // Whatever the last frames moved, the player gets on release.
        if (abs(liveBpm - appliedBpm) >= 0.01f) apply(liveBpm)
    }

    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .semantics { contentDescription = "Nudge tempo: push left to slow down, right to speed up" }
            .pointerInput(Unit) {
                widthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragStart = { held = true },
                    onDragEnd = {
                        held = false
                        scope.launch { thumb.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) }
                    },
                    onDragCancel = {
                        held = false
                        scope.launch { thumb.animateTo(0f) }
                    },
                ) { change, dx ->
                    change.consume()
                    val travel = (widthPx / 2f - TRAVEL_INSET_PX).coerceAtLeast(1f)
                    scope.launch { thumb.snapTo((thumb.value + dx / travel).coerceIn(-1f, 1f)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val cy = size.height / 2f
            val half = size.width / 2f
            // Track, then the fill from centre to the thumb in the accent.
            drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = CornerRadius(cy, cy))
            // Grip ridges, like the edge of a platter, sliding with the bend.
            val gap = 14f
            val shift = ((ridgePhase * gap) % gap + gap) % gap
            var rx = shift
            while (rx < size.width) {
                drawLine(Color.White.copy(alpha = 0.07f), Offset(rx, cy - 10f), Offset(rx, cy + 10f), 2f)
                rx += gap
            }
            val tx = half + thumb.value * (half - TRAVEL_INSET_PX)
            val left = minOf(half, tx)
            drawRoundRect(
                accent.copy(alpha = 0.35f),
                topLeft = Offset(left, cy - 6f),
                size = Size(abs(tx - half), 12f),
                cornerRadius = CornerRadius(6f, 6f),
            )
            drawLine(Color.White.copy(alpha = 0.35f), Offset(half, cy - 14f), Offset(half, cy + 14f), 2f)
            drawCircle(accent, radius = 17f + 4f * abs(thumb.value), center = Offset(tx, cy))
        }
        Text("−", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp))
        Text("+", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp))
        if (held) {
            Text(
                SpeedUnitFormat.bpm(liveBpm),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
            )
        }
    }
}

private object SpeedUnitFormat {
    fun bpm(v: Float) = tf.monochrome.android.audio.SpeedUnit.formatBpm(v)
}

/** BPM a second at full push. */
private const val MAX_RATE = 12f
private const val MIN_BPM = 20f
private const val MAX_BPM = 400f
private const val APPLY_EVERY_NANOS = 80_000_000L
private const val TRAVEL_INSET_PX = 60f
/** How fast the bend rate catches up with the push, per second. */
private const val PLATTER_RESPONSE = 6f
/** Ridges scrolled per BPM bent. */
private const val RIDGES_PER_BPM = 3f

/**
 * Type a tempo: opened by long-pressing the BPM number. Decimal keyboard,
 * focused on open, Done sets it. An ordinary dialog — it holds no glass.
 */
@Composable
fun BpmEntryDialog(current: Float, onDismiss: () -> Unit, onSet: (Float) -> Unit) {
    var text by remember { mutableStateOf(String.format(java.util.Locale.US, "%.1f", current)) }
    val parsed = text.replace(',', '.').toFloatOrNull()?.takeIf { it in MIN_BPM..MAX_BPM }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set BPM") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                singleLine = true,
                suffix = { Text("BPM") },
                isError = parsed == null,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { parsed?.let(onSet) },
                ),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) { Text("Set") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
