package tf.monochrome.android.audio.atmos

import java.nio.ByteBuffer

/**
 * JNI bridge to the native channel placer (cpp/atmos/render/channel_placer.h):
 * a multichannel bed folded to stereo with each channel where the spatial map
 * put it — through measured HRIRs for headphones, or an equal-power pan.
 *
 * Lives in the Atmos library, beside the renderer it drives; [available]
 * follows that library's load.
 */
object ChannelPlacerNative {

    val available: Boolean get() = AtmosNative.isAvailable

    /** A placer for [channels] channels at [sampleRate]; [lfeIndex] -1 for none. 0 if the library is missing. */
    fun create(sampleRate: Int, channels: Int, lfeIndex: Int): Long =
        if (available) nativeCreate(sampleRate, channels, lfeIndex) else 0L

    @JvmStatic external fun nativeCreate(sampleRate: Int, channels: Int, lfeIndex: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)

    /** Radians (0 ahead, negative left) and linear gains, one per channel. */
    @JvmStatic external fun nativeSetPlacement(handle: Long, az: FloatArray, el: FloatArray, gain: FloatArray)

    @JvmStatic external fun nativeSetMode(
        handle: Long,
        binaural: Boolean,
        strength: Float,
        heightVirtualization: Boolean,
        bassManagement: Boolean,
        crossoverHz: Int,
    )

    @JvmStatic external fun nativeReset(handle: Long)

    /** [db]: [TARGET_POINTS] values at [targetFreq], the headphone target the render is equalized to. */
    @JvmStatic external fun nativeSetTarget(handle: Long, db: FloatArray)

    /** Points in a headphone target, as ChannelPlacer::kTargetPoints. */
    const val TARGET_POINTS = 64

    /** Frequency of target point [i]: a log grid from 20 Hz to 20 kHz, as ChannelPlacer::targetFreq. */
    fun targetFreq(i: Int): Double = 20.0 * Math.pow(1000.0, i / (TARGET_POINTS - 1).toDouble())

    /** [planar]: channel c at float c * [stride]; [stereoOut]: 2 * [numFrames] interleaved floats. */
    @JvmStatic external fun nativeProcess(handle: Long, planar: ByteBuffer, stride: Int, numFrames: Int, stereoOut: ByteBuffer)
}
