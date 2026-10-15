package tf.monochrome.android.audio.meter

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One reading of the dynamic range meter: [dr] in dB, or null while it has
 * none (under one 3 s block, or silence); [seconds] of audio measured since it
 * was last reset; and the whole [blocks] the reading is over.
 */
data class DynamicRangeReading(val dr: Float?, val seconds: Float, val blocks: Int) {
    companion object {
        /** cpp's DynamicRangeMeter::kNone: "no reading". */
        private const val NONE = -999f

        internal fun from(values: FloatArray): DynamicRangeReading = DynamicRangeReading(
            dr = values.getOrNull(0)?.takeIf { it > NONE && it.isFinite() },
            seconds = values.getOrNull(1)?.takeIf { it.isFinite() && it >= 0f } ?: 0f,
            blocks = values.getOrNull(2)?.takeIf { it.isFinite() && it >= 0f }?.toInt() ?: 0,
        )
    }
}

/**
 * Whether a reading is a track's dynamic range, to keep. Pure, so it is tested
 * without the native meter.
 *
 * Only a whole, clean play counts: the meter heard the track from its start to
 * its end, with no seek in between. A seek skips or repeats audio, a skip ends
 * it early, and a crossfade plays its last seconds on another player the meter
 * cannot hear; each leaves the track unmeasured rather than measured wrong. It
 * gets its value on a later clean play.
 */
object DynamicRangeRules {

    /** How far the audio measured may be from the track's length: a little either way at a gapless join. */
    const val LENGTH_SLACK_SECONDS = 2f

    /** ...or this share of it, for a long track whose tagged length is an estimate. */
    const val LENGTH_SLACK_SHARE = 0.02f

    /** What a real master's DR can be; anything outside is a fault, not a reading. */
    const val MIN_DR = -3f
    const val MAX_DR = 40f

    /**
     * The DR to keep for a track [durationSeconds] long, rounded to a tenth of
     * a dB, or null when [reading] is not a whole, [clean] play of it.
     */
    fun accept(reading: DynamicRangeReading?, durationSeconds: Int, clean: Boolean): Float? {
        if (!clean || reading == null || durationSeconds <= 0) return null
        val dr = reading.dr ?: return null
        if (reading.blocks < 1 || dr < MIN_DR || dr > MAX_DR) return null
        val slack = maxOf(LENGTH_SLACK_SECONDS, durationSeconds * LENGTH_SLACK_SHARE)
        if (abs(reading.seconds - durationSeconds) > slack) return null
        return (dr * 10f).roundToInt() / 10f
    }

    /**
     * [accept] for the meter's [current] reading, else for the one it held
     * when the stream's format last changed ([beforeFormatChange]): at a
     * gapless join to a track of another layout, the next track's first
     * buffer resets the meter before the player says the track changed.
     */
    fun acceptEither(
        current: DynamicRangeReading?,
        beforeFormatChange: DynamicRangeReading?,
        durationSeconds: Int,
        clean: Boolean,
    ): Float? = accept(current, durationSeconds, clean) ?: accept(beforeFormatChange, durationSeconds, clean)
}
