package tf.monochrome.android.ui.mixer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Segmented vertical VU meter.
 *
 * A recessed rounded track with thin horizontal segments that light up from
 * the bottom based on the current level. Only active (and the held peak)
 * segments are painted, so the inactive track stays clean instead of showing
 * a column of faint dots. Colours run low → warning → clip near the top.
 *
 * @param level   current level in dB (typically -60 … +6), read where it is
 *                drawn: the meter moves on every poll, and taking it as a value
 *                recomposed the whole strip around it ~120 times a second.
 * @param muted   when true the bar drops to zero and dims.
 */
@Composable
fun VuMeter(
    level: () -> Float,
    muted: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val warningColor = lerp(accentColor, Color(0xFFFFD166), 0.55f)
    val clipColor = lerp(accentColor, MaterialTheme.colorScheme.error, 0.80f)
    val lowColor = accentColor.copy(alpha = 0.80f)
    val trackColor = Color.Black.copy(alpha = 0.38f)

    // Normalise dB -> 0..1 fraction  (-60 dB -> 0,  0 dB -> 1,  +6 dB -> clamp 1)
    val currentLevel by rememberUpdatedState(level)
    val currentMuted by rememberUpdatedState(muted)
    fun target(): Float = if (currentMuted) 0f else ((currentLevel() + 60f) / 60f).coerceIn(0f, 1f)

    // What animateFloatAsState did, without being a composition input: the same
    // Animatable (0.01 visibility threshold), the same spring, and each new
    // level started from the running one's velocity. The first value is read
    // without observation, so the meter's own scope never recomposes for it.
    val animated = remember { Animatable(Snapshot.withoutReadObservation { target() }, VU_THRESHOLD) }
    LaunchedEffect(animated) {
        snapshotFlow { target() }.collect { next ->
            launch { if (next != animated.targetValue) animated.animateTo(next, VuSpring) }
        }
    }

    // ── Peak hold — frame-driven ballistics ────────────────────────────
    // Hold the maximum for 700 ms, then fall at ~0.35 full-scale/s
    // (≈21 dB/s), floored at the live level. Frame-clock driven, so the
    // decay speed is identical at 60 and 120 Hz, and the old stuck-segment
    // bug (a fixed 0.04 step per 800 ms effect restart — 20 s to drain a
    // full-scale peak, hanging over an empty meter) is gone. Sleeps while
    // the meter is fully idle.
    var peak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val holdNanos = 700_000_000L
        var lastFrame = 0L
        var heldSince = 0L
        while (isActive) {
            if (peak <= 0f && animated.value <= 0f) {
                snapshotFlow { animated.value }.first { it > 0f }
                lastFrame = 0L
            }
            withFrameNanos { now ->
                val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1e9f
                lastFrame = now
                val shown = animated.value
                if (shown >= peak) {
                    peak = shown
                    heldSince = now
                } else if (now - heldSince > holdNanos) {
                    peak = (peak - dt * 0.35f).coerceAtLeast(shown)
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .width(7.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(3.5.dp))
    ) {
        val w = size.width
        val h = size.height
        val segH = 3.dp.toPx()
        val gapH = 1.2.dp.toPx()
        val stepH = segH + gapH
        val segCount = (h / stepH).toInt().coerceAtLeast(1)

        // Recessed track
        drawRect(color = trackColor)

        val activeSegs = (segCount * animated.value).toInt()
        val peakSeg = (segCount * peak).toInt().coerceIn(0, segCount - 1)

        for (i in 0 until segCount) {
            val isActive = i < activeSegs
            val isPeak = i == peakSeg && peak > 0.01f
            if (!isActive && !isPeak) continue   // keep the inactive track clean

            val segY = h - (i + 1) * stepH
            val fraction = i.toFloat() / segCount
            val color = when {
                fraction > 0.88f -> clipColor
                fraction > 0.72f -> warningColor
                fraction > 0.40f -> accentColor
                else             -> lowColor
            }
            drawRect(
                color   = if (isPeak && !isActive) color.copy(alpha = 0.85f) else color,
                topLeft = Offset(0f, segY),
                size    = Size(w, segH)
            )
        }
    }
}

// animateFloatAsState's own defaults for a Float: the spring is given the
// same 0.01 threshold the Animatable stops at.
private const val VU_THRESHOLD = 0.01f
private val VuSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = VU_THRESHOLD,
)
