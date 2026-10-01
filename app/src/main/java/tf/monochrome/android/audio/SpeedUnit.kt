package tf.monochrome.android.audio

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the speed control reads in. Presentation only — the stored speed is a
 * ratio whichever is chosen — but each unit answers a different question:
 * how much faster (multiplier), how much higher (semitones), or how fast the
 * music now goes (BPM: the track's detected tempo times the speed).
 */
enum class SpeedUnit {
    MULTIPLIER,
    SEMITONES,
    BPM;

    /**
     * The speed in this unit, for the player's speed chip and the panel's
     * readout. [trackBpm] is the song's own tempo; while it is unknown the
     * BPM unit shows the multiplier, so the chip never reads as broken.
     */
    fun format(speed: Float, trackBpm: Float?): String = when (this) {
        MULTIPLIER -> PitchRatio.formatSpeed(speed, semitoneUnit = false)
        SEMITONES -> PitchRatio.formatSpeed(speed, semitoneUnit = true)
        BPM -> playedBpm(speed, trackBpm)?.let { "${it.roundToInt()} BPM" }
            ?: PitchRatio.formatSpeed(speed, semitoneUnit = false)
    }

    companion object {
        /** From the two stored switches; BPM wins over semitones. */
        fun of(semitones: Boolean, bpm: Boolean): SpeedUnit = when {
            bpm -> BPM
            semitones -> SEMITONES
            else -> MULTIPLIER
        }

        /** What the listener hears: the track's tempo at [speed]. */
        fun playedBpm(speed: Float, trackBpm: Float?): Float? =
            trackBpm?.takeIf { it > 0f }?.let { it * speed }

        /** The panel's BPM readout, to a tenth. */
        fun formatBpm(bpm: Float): String = String.format(Locale.US, "%.1f BPM", bpm)

        /**
         * The speed that plays [trackBpm] at [targetBpm], kept to what the
         * player allows.
         */
        fun speedFor(targetBpm: Float, trackBpm: Float): Float =
            (targetBpm / trackBpm).coerceIn(PitchRatio.MIN_SPEED, PitchRatio.MAX_SPEED)

        /**
         * One step of the BPM stepper: to the next whole BPM in [direction]
         * (+1 or -1), so 127.6 goes to 128 rather than 128.6 and a DJ lands
         * on round numbers.
         */
        fun stepBpm(playedBpm: Float, direction: Int): Float {
            val rounded = playedBpm.roundToInt().toFloat()
            return when {
                direction > 0 && rounded > playedBpm + 0.05f -> rounded
                direction < 0 && rounded < playedBpm - 0.05f -> rounded
                else -> rounded + direction
            }
        }
    }
}
