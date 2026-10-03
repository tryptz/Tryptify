package tf.monochrome.android.audio.eq

import tf.monochrome.android.audio.dsp.DspNativeLoader

/**
 * The Wave Candy scope's native half (cpp/dsp/scope/wave_scope.cpp): a stereo
 * ring the audio thread pushes into, rendered once per frame into line
 * segments. One process-wide ring, like the analyzer tap that feeds it.
 */
object WaveScopeNative {
    init { DspNativeLoader.ensureLoaded() }

    /** Audio thread: [frames] interleaved stereo frames. No allocation, no lock. */
    @JvmStatic external fun nativePush(interleaved: FloatArray, frames: Int, sampleRate: Int)

    /**
     * UI thread: segments (x0, y0, x1, y1 …) for the window ending at the
     * interpolated playhead, in a [width]×[height] box; returns floats written.
     * [out] must hold (points − 1) × 4 floats per line — two lines in stereo.
     */
    @JvmStatic external fun nativeRender(
        out: FloatArray, points: Int, windowMs: Float, stereo: Boolean,
        width: Float, height: Float, gain: Float,
    ): Int

    /** RMS of the kick band over the last [frames] frames before the playhead. */
    @JvmStatic external fun nativeLowBandRms(frames: Int): Float
}
