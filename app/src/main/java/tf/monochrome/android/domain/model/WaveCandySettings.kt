package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * The Wave Candy scope on the artwork, and the kick punch that goes with it.
 * Stored as one JSON blob and synced with the rest of the settings; every
 * field is clamped on the way in and out, so a hand-edited or newer blob
 * cannot put the scope somewhere it cannot draw.
 */
@Serializable
data class WaveCandySettings(
    /** Left across the top, right across the bottom; false is one summed line. */
    val stereo: Boolean = true,
    /** How much audio the width shows, in milliseconds. */
    val windowMs: Float = 40f,
    /** Vertical gain on the waveform. */
    val gain: Float = 1f,
    /** Line thickness in dp. */
    val thicknessDp: Float = 1.6f,
    /** Line colour: the album's accent rather than white. */
    val albumColor: Boolean = false,
    /** The cover punching in on each kick. */
    val kickEnabled: Boolean = true,
    /** How far the cover punches in on a kick, as a fraction (0.05 = 5%). */
    val kickZoom: Float = 0.05f,
    /** 0 = only the hardest kicks, 1 = everything with low end. */
    val kickSensitivity: Float = 0.5f,
) {
    fun clamped() = copy(
        windowMs = windowMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS),
        gain = gain.coerceIn(0.25f, 3f),
        thicknessDp = thicknessDp.coerceIn(0.5f, 4f),
        kickZoom = kickZoom.coerceIn(0f, 0.12f),
        kickSensitivity = kickSensitivity.coerceIn(0f, 1f),
    )

    /** Onset ratio over the running average: sensitivity 0 → 2.0, 1 → 1.15. */
    val kickOnsetRatio: Float get() = 2.0f - 0.85f * kickSensitivity

    companion object {
        const val MIN_WINDOW_MS = 10f
        const val MAX_WINDOW_MS = 300f
        val DEFAULT = WaveCandySettings()
    }
}
