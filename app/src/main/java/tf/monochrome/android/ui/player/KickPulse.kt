package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

/**
 * A 0..1 pulse that jumps on every kick drum and decays — what makes the cover
 * punch in on the beat, Euphoric Hardstylez style.
 *
 * Each frame it reads the latest stereo frames through [read], low-passes them
 * to the kick's band (a one-pole at ~150 Hz), and takes the RMS. A kick is that
 * energy jumping well above its own recent average, so a quiet intro and a wall
 * of hardstyle both trigger on their kicks rather than on their loudness.
 * Off ([enabled] false) it reads nothing and holds at 0.
 */
@Composable
fun rememberKickPulse(read: ((FloatArray, FloatArray) -> Int)?, enabled: Boolean): FloatState {
    val pulse = remember { mutableFloatStateOf(0f) }
    val l = remember { FloatArray(KICK_FRAMES) }
    val r = remember { FloatArray(KICK_FRAMES) }
    val reader = rememberUpdatedState(read)
    LaunchedEffect(enabled, read != null) {
        pulse.floatValue = 0f
        if (!enabled || read == null) return@LaunchedEffect
        var average = 0f
        var cooldown = 0
        while (isActive) {
            withFrameNanos { }
            val n = reader.value?.invoke(l, r) ?: 0
            val energy = lowBandRms(l, r, n)
            val onset = energy > average * ONSET_RATIO && energy > FLOOR && cooldown == 0
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

/** RMS of the frames after a ~150 Hz one-pole low-pass (at 48 kHz). */
internal fun lowBandRms(l: FloatArray, r: FloatArray, n: Int): Float {
    if (n <= 0) return 0f
    var y = 0f
    var sum = 0f
    for (i in 0 until n) {
        y += ((l[i] + r[i]) * 0.5f - y) * LOWPASS_ALPHA
        sum += y * y
    }
    return sqrt(sum / n)
}

// The last ~21 ms at 48 kHz: about one kick's attack, so a frame's window
// holds a hit rather than averaging it into the bars around it.
private const val KICK_FRAMES = 1024
private const val LOWPASS_ALPHA = 0.02f
private const val ONSET_RATIO = 1.45f
private const val FLOOR = 0.015f
private const val AVERAGE_RATE = 0.06f
// Per 60 fps frame: back to ~10% in about 150 ms.
private const val DECAY = 0.86f
// At least ~120 ms between kicks — hardstyle tops out near 160 BPM.
private const val COOLDOWN_FRAMES = 7
