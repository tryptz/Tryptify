// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.replaygain

import tf.monochrome.android.audio.dsp.DspNativeLoader
import java.nio.ByteBuffer

/**
 * ReplayGain's native half (cpp/dsp/replaygain/): the stage that applies the
 * gain to the samples, in the same C++ DSP library as the mixer, the inflator
 * and the compressor, and tested with them on the desktop
 * (cpp/dsp/tests/replay_gain_test.cpp). [ReplayGainProcessor] is its Media3
 * shell.
 */
internal object ReplayGainNative {
    init { DspNativeLoader.ensureLoaded() }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativePrepare(handle: Long, sampleRate: Int, channels: Int)

    /** The start of a stream, at [gain] at once. */
    @JvmStatic external fun nativeReset(handle: Long, gain: Float)

    /**
     * [frames] whole frames of [input] from byte [inputOffset] to the start of
     * [output], both direct, native order. With [followLufs] a target (above
     * [FOLLOW_OFF]) song [song] is measured and turned down to it; otherwise
     * it glides to [gain]. Returns the gain it is at after the buffer, or -1,
     * and nothing written, when a buffer is not direct.
     */
    @JvmStatic external fun nativeProcess(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        output: ByteBuffer,
        frames: Int,
        isFloat: Boolean,
        gain: Float,
        followLufs: Float,
        song: Int,
    ): Float

    /** The following song's integrated loudness so far, LUFS; below -999 with no reading yet. */
    @JvmStatic external fun nativeMeasuredLufs(handle: Long): Float

    /** "Not following", for nativeProcess's followLufs: a sentinel, as the library builds with -ffast-math. */
    const val FOLLOW_OFF = -1000f
}
