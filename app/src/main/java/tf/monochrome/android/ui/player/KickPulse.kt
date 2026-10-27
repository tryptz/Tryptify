package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.pow
import kotlinx.coroutines.isActive
import tf.monochrome.android.audio.eq.WaveScopeNative

/**
 * A 0..1 pulse that jumps on every kick drum and decays — what makes the cover
 * punch in on the beat, Euphoric Hardstylez style.
 *
 * Each frame the native scope gives the RMS of the kick band (~150 Hz) over
 * the last 1024 frames at the smoothed playhead. A kick is that energy jumping
 * above [onsetRatio] × its own recent average, so a quiet intro and a wall of
 * hardstyle both trigger on their kicks rather than on their loudness.
 * Off ([enabled] false) it reads nothing and holds at 0.
 */
@Composable
fun rememberKickPulse(enabled: Boolean, onsetRatio: Float = 1.45f): FloatState {
    val pulse = remember { mutableFloatStateOf(0f) }
    val ratio = rememberUpdatedState(onsetRatio)
    LaunchedEffect(enabled) {
        pulse.floatValue = 0f
        if (!enabled) return@LaunchedEffect
        var average = 0f
        var cooldownUntil = 0L
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            // Each rate below is per 60 fps frame, raised to the frames of 60
            // fps this frame stood for, so the pulse decays at the same speed
            // on a 120 Hz panel and under a frame rate limit.
            val frames = if (last == 0L) 1f else ((now - last) / NANOS_PER_60FPS_FRAME).coerceIn(0f, 30f)
            last = now
            val energy = WaveScopeNative.nativeLowBandRms(KICK_FRAMES)
            val onset = energy > average * ratio.value && energy > FLOOR && now >= cooldownUntil
            average += (energy - average) * (1f - (1f - AVERAGE_RATE).pow(frames))
            if (onset) {
                pulse.floatValue = 1f
                cooldownUntil = now + COOLDOWN_NANOS
            } else {
                pulse.floatValue *= DECAY.pow(frames)
            }
        }
    }
    return pulse
}

/** RMS of the frames after a ~150 Hz one-pole low-pass — the native twin, kept for tests. */
internal fun lowBandRms(l: FloatArray, r: FloatArray, n: Int): Float {
    if (n <= 0) return 0f
    var y = 0f
    var sum = 0f
    for (i in 0 until n) {
        y += ((l[i] + r[i]) * 0.5f - y) * 0.02f
        sum += y * y
    }
    return kotlin.math.sqrt(sum / n)
}

private const val KICK_FRAMES = 1024
private const val FLOOR = 0.015f
private const val AVERAGE_RATE = 0.06f
// Per 60 fps frame: back to ~10% in about 150 ms.
private const val DECAY = 0.86f
// At least ~120 ms between kicks — hardstyle tops out near 160 BPM.
private const val COOLDOWN_NANOS = 117_000_000L
private const val NANOS_PER_60FPS_FRAME = 1_000_000_000f / 60f
