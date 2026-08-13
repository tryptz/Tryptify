package tf.monochrome.android.ui.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.GlobeFxSettings
import tf.monochrome.android.ui.theme.reduceMotion
import kotlin.math.exp

/**
 * One frame of the globe's reaction to the music.
 *
 * [bass] and [treble] are 0..1 transient strengths, not levels — see the
 * adaptive floor below. [phase] is the travelling waves' position, in radians,
 * and only ever increases.
 */
internal data class GlobePulse(
    val bass: Float = 0f,
    val treble: Float = 0f,
    val phase: Float = 0f,
)

// Bin edges into the tap's 256 log-spaced bins spanning 20 Hz..20 kHz, where
// bin = log10(f / 20) / 3 × 256.
//
// 40–160 Hz is the kick and the bass fundamentals; 3–12 kHz is where hats,
// snare rattle and air live. The gap between them is deliberate — the midrange
// is where the vocal and most of the mix's energy sits, so a band that included
// it would track "is the song loud" and both waves would move together, which
// is the single-wave look this file exists to avoid.
private const val BASS_BIN_START = 26
private const val BASS_BIN_END = 77
private const val TREBLE_BIN_START = 186
private const val TREBLE_BIN_END = 237

// Adaptive floor, the same device the lyric bass pump uses and for the same
// reason: the tap emits pink-compensated dB, and in real music these bands sit
// well above the 0 dB reference — so an absolute threshold pins at maximum on
// anything loud and the globe just stays deformed. Tracking a slow floor and
// driving from how far the level pokes above it gives something that rests near
// zero between hits whatever the mix level is.
private const val FLOOR_FALL_SEC = 0.4f
private const val FLOOR_RISE_SEC = 2.5f
private const val BASS_RANGE_DB = 8f
private const val TREBLE_RANGE_DB = 10f

// Treble transients are shorter than bass ones and a slow release smears hats
// into one continuous buzz, so the two bands get their own envelopes.
private const val BASS_ATTACK_SEC = 0.035f
private const val BASS_RELEASE_SEC = 0.30f
private const val TREBLE_ATTACK_SEC = 0.015f
private const val TREBLE_RELEASE_SEC = 0.12f

private const val TWO_PI = (2.0 * Math.PI).toFloat()

/**
 * Drive the globe's waves from the shared FFT tap.
 *
 * Returns a [State] that changes every frame while the effect is live. Read it
 * from a draw lambda and nowhere else: that keeps the invalidation in the draw
 * phase, so a wobbling coastline never recomposes the screen around it.
 *
 * Bails before taking the analyzer stake when the effect is off, when nothing
 * is playing, or when animations are disabled — in all three cases there is
 * nothing to show, and the FFT is not free enough to run for a value no pixel
 * will read.
 */
@Composable
internal fun rememberGlobePulse(
    tap: SpectrumAnalyzerTap?,
    fx: GlobeFxSettings,
    playing: Boolean,
): State<GlobePulse> {
    val pulse = remember { mutableStateOf(GlobePulse()) }
    // Read the live settings inside the frame loop rather than keying the loop
    // on them. Keying would tear the loop down and stand it back up on every
    // pixel of slider travel, and the phase would restart from zero each time —
    // so dragging Wave speed, the one control whose whole subject is continuous
    // motion, would make the coastline snap on every frame of the drag.
    val settings = rememberUpdatedState(fx)
    val live = tap != null && playing && fx.enabled && !reduceMotion()

    // Settle back to rest rather than freezing mid-crest when the effect stops.
    if (!live || tap == null) {
        if (pulse.value != GlobePulse()) pulse.value = GlobePulse()
        return pulse
    }

    DisposableEffect(tap) {
        tap.acquire()
        onDispose { tap.release() }
    }

    LaunchedEffect(tap) {
        var bassEnv = 0f
        var trebleEnv = 0f
        var bassFloor = 0f
        var trebleFloor = 0f
        var floorsSeeded = false
        var phase = 0f
        var lastNanos = -1L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos < 0) 0.016f
                else ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = now

                val bins = tap.spectrumBins.value
                val bassDb = bins.meanOf(BASS_BIN_START, BASS_BIN_END)
                val trebleDb = bins.meanOf(TREBLE_BIN_START, TREBLE_BIN_END)

                if (!floorsSeeded) {
                    bassFloor = bassDb
                    trebleFloor = trebleDb
                    floorsSeeded = true
                }
                bassFloor += (bassDb - bassFloor) *
                    (1f - exp(-dt / if (bassDb < bassFloor) FLOOR_FALL_SEC else FLOOR_RISE_SEC))
                trebleFloor += (trebleDb - trebleFloor) *
                    (1f - exp(-dt / if (trebleDb < trebleFloor) FLOOR_FALL_SEC else FLOOR_RISE_SEC))

                val bassRaw = ((bassDb - bassFloor) / BASS_RANGE_DB).coerceIn(0f, 1f)
                val trebleRaw = ((trebleDb - trebleFloor) / TREBLE_RANGE_DB).coerceIn(0f, 1f)
                bassEnv = bassEnv.follow(bassRaw, dt, BASS_ATTACK_SEC, BASS_RELEASE_SEC)
                trebleEnv = trebleEnv.follow(trebleRaw, dt, TREBLE_ATTACK_SEC, TREBLE_RELEASE_SEC)

                // The wave always travels, and travels faster when the track is
                // driving. A phase that only advanced on transients would make
                // the coastline lurch and then hold, which reads as dropped
                // frames rather than as motion.
                phase += dt * settings.value.waveSpeed * (1.1f + 2.6f * bassEnv)
                if (phase > TWO_PI) phase -= TWO_PI

                pulse.value = GlobePulse(bass = bassEnv, treble = trebleEnv, phase = phase)
            }
        }
    }
    return pulse
}

/** Mean dB across an inclusive bin range, tolerant of a shorter array. */
private fun FloatArray.meanOf(start: Int, end: Int): Float {
    if (isEmpty()) return 0f
    val lo = start.coerceIn(0, size - 1)
    val hi = end.coerceIn(lo, size - 1)
    var sum = 0f
    for (i in lo..hi) sum += this[i]
    return sum / (hi - lo + 1)
}

/** One-pole envelope with separate attack and release time constants. */
private fun Float.follow(target: Float, dt: Float, attackSec: Float, releaseSec: Float): Float {
    val tau = if (target > this) attackSec else releaseSec
    return this + (target - this) * (1f - exp(-dt / tau))
}
