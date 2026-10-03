package tf.monochrome.android.audio.eq

import java.util.concurrent.atomic.AtomicInteger
import tf.monochrome.android.audio.dsp.DspNativeLoader

/**
 * The EBU R128 loudness meter's native half (cpp/dsp/meter/): fed by
 * [SpectrumAnalyzerTap] from the audio thread, read by the Audio Pipeline panel
 * and the mixer's master strip.
 *
 * Measured where the tap sits — after the mixer, the fold-down and both EQs,
 * before the speed stages — so it is the loudness of what Tryptify hands to the
 * output, at the source's own speed.
 *
 * Runs only while something is reading it, like the analyzer itself: a
 * [acquire] from zero readers starts the measurement afresh, so Integrated and
 * Range always mean "since this meter came on screen, or since the track
 * changed, whichever is later". Nothing is measured while nobody is looking.
 */
object LoudnessNative {
    init { DspNativeLoader.ensureLoaded() }

    private val readers = AtomicInteger(0)

    /** Read on the audio thread, once per buffer. */
    @Volatile
    var active: Boolean = false
        private set

    fun acquire() {
        if (readers.incrementAndGet() == 1) {
            nativeRequestReset()
            active = true
        }
    }

    fun release() {
        if (readers.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) active = false
    }

    /** A new track, or a tap on a readout: Integrated, Range and peak start over. */
    fun reset() = nativeRequestReset()

    /** The current reading, or null when nothing has been measured yet. */
    fun read(): LoudnessReading? {
        val out = FloatArray(5)
        nativeRead(out)
        return LoudnessReading.from(out)
    }

    /** Audio thread: [frames] interleaved frames of [channels]. No allocation, no lock. */
    @JvmStatic external fun nativePush(interleaved: FloatArray, frames: Int, channels: Int, sampleRate: Int)

    @JvmStatic private external fun nativeRead(out: FloatArray)

    @JvmStatic private external fun nativeRequestReset()
}

/**
 * One reading of the meter. Each field is null until there is something to
 * report: Momentary needs 400 ms of audio, Short-term and Range 3 s, Integrated
 * one block above the −70 LUFS gate. Silence is a reading, not a null — it
 * comes back at [SILENCE].
 */
data class LoudnessReading(
    val momentary: Float?,
    val shortTerm: Float?,
    val integrated: Float?,
    val range: Float?,
    val truePeak: Float?,
) {
    companion object {
        /** The native floor: digital silence and anything quieter. */
        const val SILENCE = -120f

        /** cpp's LoudnessMeter::kNone — "no reading yet". */
        private const val NONE = -999f

        internal fun from(values: FloatArray): LoudnessReading? {
            fun slot(i: Int) = values.getOrNull(i)?.takeIf { it > NONE && it.isFinite() }
            val r = LoudnessReading(slot(0), slot(1), slot(2), slot(3), slot(4))
            return r.takeIf { it.momentary != null || it.truePeak != null }
        }
    }
}
