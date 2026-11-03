// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tap to sync: the audio offset measured by the player tapping along to a
 * click track, for headphones — Bluetooth above all — that deliver sound a
 * few hundred milliseconds after the phone sends it.
 *
 * The track plays through the game's own transport, and each tap is placed on
 * the same clock a lane press is judged on, before any offset. So the distance
 * from a tap to its click is exactly what the game would judge as late, and
 * the median of those distances is the offset to set — not a correction to the
 * current one.
 *
 * Every tap counts from the first. The beat's phase is read from all of them
 * at once (their circular mean, then the median around it), so no single tap
 * decides it. That leaves which click the taps answer: at [GlyphSyncTrack.BPM]
 * the clicks are closer together than Bluetooth can be late, so a phase fits
 * several offsets a beat apart. The first tap settles it. The track opens on
 * silence, so the first tap is either a reaction to the first click (at most
 * [REACTION_MS] after it, or a little ahead) or a tap in time on a later one;
 * of the offsets within reach of the setting, the latest one that leaves the
 * first tap like that is taken, and [couldBeAClickOff] says when two did.
 *
 * It used to anchor everything on the first tap: a first tap is a reaction,
 * a normal 220-350 ms late, which threw the second tap away as a repeat (the
 * count did not move for the first few taps) and could read a whole click
 * off (150 ms headphones read as 570 ms). The first tap now only picks the
 * click; the reading leaves it out. Two taps closer than [DOUBLE_TAP_MS] are
 * one tap.
 */
class GlyphTapSync(
    private val firstBeatSeconds: Float = GlyphSyncTrack.FIRST_BEAT_SECONDS,
    private val beatSeconds: Float = GlyphSyncTrack.BEAT_SECONDS,
) {
    /** Each counted tap, in seconds from the first click, before any offset. */
    private val sinces = ArrayList<Float>()

    /** Taps counted so far. */
    val taps: Int get() = sinces.size

    val done: Boolean get() = taps >= NEEDED

    /**
     * A tap at [songSeconds] on the track's clock, before any offset. True when
     * it counted toward the reading.
     */
    fun tap(songSeconds: Float): Boolean {
        if (done) return false
        val since = songSeconds - firstBeatSeconds
        if (since * 1000f < -EARLIEST_MS) return false
        if (sinces.isNotEmpty() && (since - sinces.last()) * 1000f < DOUBLE_TAP_MS) return false
        sinces += since
        reading = null
        return true
    }

    private class Reading(val offsetMs: Float, val deltasMs: List<Float>, val ambiguous: Boolean)

    private var reading: Reading? = null

    /** The taps read together; null until [MIN_TAPS] have counted. */
    private fun read(): Reading? {
        if (taps < MIN_TAPS) return null
        reading?.let { return it }
        val rest = sinces.subList(1, sinces.size)
        // The beat's phase: the circular mean of where the taps fall in it,
        // then the median of each tap's distance from that, against strays.
        var sinSum = 0.0
        var cosSum = 0.0
        for (s in rest) {
            val angle = 2.0 * PI * (s / beatSeconds)
            sinSum += sin(angle)
            cosSum += cos(angle)
        }
        val mean = (atan2(sinSum, cosSum) / (2.0 * PI) * beatSeconds).toFloat()
        val phase = mean + median(rest.map { wrap(it - mean) })
        // Every offset a whole beat from the phase that the setting can hold.
        val candidates = (-3..3).map { phase + it * beatSeconds }
            .filter { it * 1000f >= -EARLIEST_MS && it * 1000f <= LATEST_MS }
        val firstTap = sinces[0]
        val plausible = candidates.filter { plausibleFirstTap(firstTap - it) }
        val offset = plausible.maxOrNull()
            ?: candidates.minByOrNull { abs((firstTap - it) * 1000f - TYPICAL_REACTION_MS) }
            ?: phase
        val deltas = rest.map { (offset + wrap(it - offset)) * 1000f }
        return Reading(median(deltas), deltas, plausible.size > 1).also { reading = it }
    }

    /**
     * The first tap [secondsAfter] the offset's first click: a reaction to
     * it, or a tap in time on a later click.
     */
    private fun plausibleFirstTap(secondsAfter: Float): Boolean {
        val ms = secondsAfter * 1000f
        if (ms >= -EARLIEST_MS && ms <= REACTION_MS) return true
        val clicks = (secondsAfter / beatSeconds).roundToInt()
        return clicks >= 1 && abs(ms - clicks * beatSeconds * 1000f) <= IN_TIME_MS
    }

    /** [seconds] folded into half a beat either side of zero. */
    private fun wrap(seconds: Float): Float {
        val folded = ((seconds / beatSeconds) + 0.5f).let { it - floor(it) } - 0.5f
        return folded * beatSeconds
    }

    /** The offset the taps say, in whole milliseconds; null until [MIN_TAPS] have counted. */
    val offsetMs: Int?
        get() = read()?.offsetMs?.roundToInt()

    /**
     * How evenly the taps fell, as a standard deviation from the spread of
     * the middle half of them (so a stray tap or two does not swing it); null
     * with too few. Not the median absolute deviation: with taps that land
     * either side of the beat by turns, an odd count put the median on one
     * side and read the spread as nothing.
     */
    val spreadMs: Int?
        get() {
            val reading = read() ?: return null
            val sorted = reading.deltasMs.sorted()
            return ((quantile(sorted, 0.75f) - quantile(sorted, 0.25f)) / IQR_TO_SD).roundToInt()
        }

    /** The [q] quantile of [sorted], read between its two nearest values. */
    private fun quantile(sorted: List<Float>, q: Float): Float {
        val position = q * (sorted.size - 1)
        val below = floor(position).toInt()
        val above = minOf(below + 1, sorted.size - 1)
        return sorted[below] + (sorted[above] - sorted[below]) * (position - below)
    }

    /** Taps that wander more than this read as an uncertain offset. */
    val steady: Boolean get() = (spreadMs ?: Int.MAX_VALUE) <= STEADY_MS

    /**
     * The taps fit a click later too: a player who sat out the first click
     * and came in on the second reads one click less than this.
     */
    val couldBeAClickOff: Boolean
        get() = read()?.ambiguous ?: false

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    companion object {
        const val NEEDED = 16
        const val MIN_TAPS = 8
        /** Nobody taps further ahead of a click they are waiting for. */
        const val EARLIEST_MS = 100
        /** No headphones are later than this; the offset's own limit. */
        const val LATEST_MS = GlyphCalibration.MAX_OFFSET_MS
        const val STEADY_MS = 35
        /** The slowest first reaction to the first click after the silence. */
        const val REACTION_MS = 450
        /** A first tap's usual reaction, when nothing else settles the click. */
        const val TYPICAL_REACTION_MS = 220
        /** How close a first tap in time on a later click comes to it. */
        const val IN_TIME_MS = 80
        /** Closer than this, two taps are one finger landing twice. */
        const val DOUBLE_TAP_MS = 80
        /** A normal spread's interquartile range in standard deviations. */
        private const val IQR_TO_SD = 1.349f
    }
}

/**
 * The click track tap to sync plays: a lead-in of silence, then a click on
 * every beat at [BPM], the first of each bar higher, as the metronome's. Made
 * here as a WAV rather than shipped, so it is exactly the beat [GlyphTapSync]
 * measures against.
 */
object GlyphSyncTrack {
    const val BPM = 140
    const val BEAT_SECONDS = 60f / BPM
    /** Silence before the first click: time to put the phone down and listen. */
    const val FIRST_BEAT_SECONDS = 2f
    const val BEATS = 48
    const val SAMPLE_RATE = 44_100

    /** Where the last click has sounded and rung out. */
    val endSeconds: Float get() = FIRST_BEAT_SECONDS + BEATS * BEAT_SECONDS + 0.5f

    fun wav(): ByteArray {
        val frames = (endSeconds * SAMPLE_RATE).toInt()
        val samples = ShortArray(frames)
        val clickFrames = (CLICK_SECONDS * SAMPLE_RATE).toInt()
        for (beat in 0 until BEATS) {
            val start = ((FIRST_BEAT_SECONDS + beat * BEAT_SECONDS) * SAMPLE_RATE).roundToInt()
            val hz = if (beat % 4 == 0) DOWNBEAT_HZ else BEAT_HZ
            for (i in 0 until clickFrames) {
                val t = i.toDouble() / SAMPLE_RATE
                val value = sin(2.0 * PI * hz * t) * exp(-t * DECAY) * AMPLITUDE
                samples[start + i] = (value * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        val bytes = ByteArrayOutputStream(44 + frames * 2)
        DataOutputStream(bytes).use { out ->
            fun intLe(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun shortLe(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            out.writeBytes("RIFF")
            intLe(36 + frames * 2)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            intLe(16)
            shortLe(1)
            shortLe(1)
            intLe(SAMPLE_RATE)
            intLe(SAMPLE_RATE * 2)
            shortLe(2)
            shortLe(16)
            out.writeBytes("data")
            intLe(frames * 2)
            for (s in samples) shortLe(s.toInt())
        }
        return bytes.toByteArray()
    }

    private const val CLICK_SECONDS = 0.04
    private const val BEAT_HZ = 1_000.0
    private const val DOWNBEAT_HZ = 1_500.0
    private const val DECAY = 90.0
    private const val AMPLITUDE = 0.6
}
