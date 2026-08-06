package tf.monochrome.android.audio.dsp.model

/**
 * One snapshot of the live audio tap for the FX chain visualizations: per-slot
 * in/out peak meters for [busIndex] plus the bus's post-fader mono waveform.
 *
 * The arrays are owned and reused by [tf.monochrome.android.audio.dsp.DspEngineManager]
 * to keep the per-frame poll allocation-free — consumers must read them, not
 * retain them across frames. [seq] increments per poll so each snapshot is a
 * distinct StateFlow emission even though the array instances repeat.
 */
class FxTapFrame(
    val seq: Long,
    val busIndex: Int,
    private val meters: FloatArray,
    val wave: FloatArray,
    val waveLen: Int,
) {
    /** Peak level in dB entering the plugin at [slot] (floor −60, −60 when idle). */
    fun inDb(slot: Int): Float = meters.getOrElse(slot * 2) { -60f }

    /** Peak level in dB leaving the plugin at [slot] (post dry/wet). */
    fun outDb(slot: Int): Float = meters.getOrElse(slot * 2 + 1) { -60f }
}
