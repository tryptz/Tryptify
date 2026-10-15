package tf.monochrome.android.audio.meter

import tf.monochrome.android.audio.dsp.DspNativeLoader

/**
 * The dynamic range meter's native half (cpp/dsp/meter/dynamic_range_*): fed
 * by [DynamicRangeTapProcessor] from the audio thread, started and read by
 * the player (DynamicRangeRecorder) for each local track it has no value for.
 *
 * Measured at the head of the chain: the track as decoded, before the mixer,
 * the effects, the volume and the speed change, so a value is the file's own
 * whatever the output. Off ([active] false) the rest of the time, so nothing
 * is measured that would not be kept.
 */
object DynamicRangeNative {
    init { DspNativeLoader.ensureLoaded() }

    /** Read on the audio thread, once per buffer. */
    @Volatile
    var active: Boolean = false
        private set

    /** A track to measure from its start: the meter starts again at the next buffer. */
    fun start() {
        nativeRequestReset()
        active = true
    }

    /** Nothing to measure. */
    fun stop() {
        active = false
    }

    /** Start the measurement again, from the next buffer: a seek back to the start. */
    fun restart() = nativeRequestReset()

    /** The reading so far. Read before [start] or [restart]: they clear it. */
    fun read(): DynamicRangeReading = readBoth().first

    /**
     * The reading so far, and the one the meter held when the stream's format
     * last changed under it. At a gapless join from stereo to 5.1, say, the
     * next track's first buffer resets the meter before the player says the
     * track changed: the finished track's reading is the second.
     */
    fun readBoth(): Pair<DynamicRangeReading, DynamicRangeReading> {
        val out = FloatArray(6)
        nativeRead(out)
        return DynamicRangeReading.from(out) to DynamicRangeReading.from(out.copyOfRange(3, 6))
    }

    /** Audio thread: [frames] interleaved frames of [channels]. No allocation, no lock. */
    @JvmStatic external fun nativePush(interleaved: FloatArray, frames: Int, channels: Int, sampleRate: Int)

    @JvmStatic private external fun nativeRead(out: FloatArray)

    @JvmStatic private external fun nativeRequestReset()
}
