package tf.monochrome.android.audio.usb

/**
 * How the exclusive USB path moves its gain to a new volume.
 *
 * Applying a new gain from one buffer to the next is a step in the waveform,
 * heard as a click on every slider move and key press ("zipper" noise). So
 * the sink moves the gain a frame at a time instead, at these rates:
 *  - up, over [RISE_SECONDS] whatever the size of the change: this is also
 *    the fade-in when the DAC starts from silence, so a stream arrives at its
 *    level over half a second rather than all at once;
 *  - down, over [FALL_SECONDS] of full scale: quick enough to feel
 *    immediate, which turning something down should be.
 *
 * Linear in gain and constant within a buffer, so the gain after any number
 * of frames is exact ([after]): a write the DAC only partly takes resumes from
 * the gain of the last frame it took, with no jump.
 *
 * Arithmetic only — called per frame on the audio thread, it allocates nothing.
 */
internal object GainRamp {
    const val RISE_SECONDS = 0.5f
    const val FALL_SECONDS = 0.03f

    /**
     * Per-frame rise toward [target] at [sampleRate]: relative to the target,
     * so reaching -24 dB from silence takes as long as reaching 0 dB. A
     * fixed slope would have arrived at a quiet level in milliseconds.
     */
    fun risePerFrame(target: Float, sampleRate: Int): Float =
        if (sampleRate <= 0) 1f else maxOf(target, 1e-4f) / (RISE_SECONDS * sampleRate)

    /** Per-frame fall at [sampleRate]: full scale in [FALL_SECONDS]. */
    fun fallPerFrame(sampleRate: Int): Float =
        if (sampleRate <= 0) 1f else 1f / (FALL_SECONDS * sampleRate)

    /** The gain [frames] frames after [start], heading for [target]. */
    fun after(start: Float, target: Float, frames: Int, rise: Float, fall: Float): Float {
        val delta = target - start
        return if (delta >= 0f) {
            start + minOf(delta, frames * rise)
        } else {
            start - minOf(-delta, frames * fall)
        }
    }
}
