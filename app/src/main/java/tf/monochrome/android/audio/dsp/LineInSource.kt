package tf.monochrome.android.audio.dsp

/**
 * The audio-thread view of a hardware stereo input.
 *
 * `MixBusProcessor` pulls one block per DSP chunk through this and hands it to
 * the native engine as its aux input pair, where each bus decides whether to
 * read it (see `BusInputSource`). Keeping the contract this narrow is what lets
 * `MixBusProcessor` stay free of Android audio APIs — the real implementation
 * (`StereoInputEngine`) owns `AudioRecord`, device enumeration and permissions,
 * none of which belong on the audio thread.
 *
 * Every member here is called from the audio thread and must not block,
 * allocate, or lock.
 */
interface LineInSource {

    /** True while a capture stream is running and its audio should reach the engine. */
    val engaged: Boolean

    /**
     * Fill [outL]/[outR] with exactly [frames] frames of captured audio,
     * zero-filling any shortfall. Returns false when nothing was supplied, in
     * which case the caller passes no aux pair to the engine at all.
     */
    fun readBlock(outL: FloatArray, outR: FloatArray, frames: Int): Boolean

    /**
     * Tell the input side which sample rate the playback pipeline is running
     * at. Capture is opened at the same rate so the framework resamples on the
     * way in and the two streams stay sample-aligned — without this, mixing
     * line-in under a 44.1 kHz track while the device captures at 48 kHz would
     * drift a full second every ~11 minutes.
     *
     * Called from `flush()` on format changes, i.e. not from the audio thread's
     * hot path, so this one may do real work.
     */
    fun onPlaybackFormat(sampleRate: Int)
}
