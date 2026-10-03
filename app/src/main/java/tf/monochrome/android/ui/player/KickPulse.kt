package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
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
        var cooldown = 0
        while (isActive) {
            withFrameNanos { }
            val energy = WaveScopeNative.nativeLowBandRms(KICK_FRAMES)
            val onset = energy > average * ratio.value && energy > FLOOR && cooldown == 0
            average += (energy - average) * AVERAGE_RATE
            if (onset) {
                pulse.floatValue = 1f
                cooldown = COOLDOWN_FRAMES
            } else {
                pulse.floatValue *= DECAY
                if (cooldown > 0) cooldown--
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
private const val COOLDOWN_FRAMES = 7
