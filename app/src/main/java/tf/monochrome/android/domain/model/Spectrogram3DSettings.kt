package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * The cover's third view: the music as a 3D landscape (a spectrogram drawn as a
 * lit, glossy surface). Time runs sideways, the newest at the right edge; pitch
 * runs into the distance, bass at the front. Stored as one JSON blob and
 * synced like [SpectrumWaterfallSettings], clamped on the way in and out.
 *
 * [quality] and [preview] are the listener's own: a theme neither sets nor
 * shares them ([withPersonalFrom]).
 */
@Serializable
data class Spectrogram3DSettings(
    /** Speed: how many seconds of music the front edge spans, right to left. */
    val secondsAcross: Float = 6f,
    /** How high the camera looks down: low is a near-flat horizon, high looks down on it. */
    val angleDeg: Float = 18f,
    /** How tall the peaks stand, 1 as shipped. */
    val peakHeight: Float = 1f,
    /** How shiny the surface is: 0 matte, 1 wet. */
    val gloss: Float = 0.6f,
    /** How much light glows through the ridges, in the album's second colour. */
    val subsurface: Float = 0.7f,
    /** 0 sharp, detailed ridges; 1 soft and rounded. */
    val smoothness: Float = 0.3f,
    /** How quickly the back fades into the cover. */
    val haze: Float = 0.4f,
    /** The lowest pitch shown, at the front, in Hz. */
    val minHz: Float = 30f,
    /** The highest pitch shown, at the back, in Hz. */
    val maxHz: Float = 16_000f,
    /** Mesh detail, [MIN_QUALITY]..[MAX_QUALITY]. Personal: older phones want fewer points. */
    val quality: Int = 2,
    /** The Settings preview is shown. Personal. */
    val preview: Boolean = true,
) {
    /** Every value in its range; a value that is not a number takes the default. */
    fun clamped(): Spectrogram3DSettings {
        val d = DEFAULT
        fun Float.c(min: Float, max: Float, def: Float) = if (isFinite()) coerceIn(min, max) else def
        val low = minHz.c(MIN_HZ, MAX_LOW_HZ, d.minHz)
        // At least two octaves between the ends, so there is a landscape to draw.
        val high = maxHz.c(MIN_HIGH_HZ, MAX_HZ, d.maxHz).coerceAtLeast(low * MIN_RANGE_RATIO).coerceAtMost(MAX_HZ)
        return copy(
            secondsAcross = secondsAcross.c(MIN_SECONDS, MAX_SECONDS, d.secondsAcross),
            angleDeg = angleDeg.c(MIN_ANGLE_DEG, MAX_ANGLE_DEG, d.angleDeg),
            peakHeight = peakHeight.c(MIN_PEAK, MAX_PEAK, d.peakHeight),
            gloss = gloss.c(0f, 1f, d.gloss),
            subsurface = subsurface.c(0f, 1f, d.subsurface),
            smoothness = smoothness.c(0f, 1f, d.smoothness),
            haze = haze.c(0f, 1f, d.haze),
            minHz = low,
            maxHz = high,
            quality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY),
        )
    }

    /** This look with [other]'s personal settings: what a theme applied over [other] gives. */
    fun withPersonalFrom(other: Spectrogram3DSettings): Spectrogram3DSettings =
        copy(quality = other.quality, preview = other.preview)

    companion object {
        val DEFAULT = Spectrogram3DSettings()

        const val MIN_SECONDS = 2f
        const val MAX_SECONDS = 20f
        const val MIN_ANGLE_DEG = 5f
        const val MAX_ANGLE_DEG = 60f
        const val MIN_PEAK = 0.25f
        const val MAX_PEAK = 2f
        const val MIN_HZ = 20f
        const val MAX_LOW_HZ = 2_000f
        const val MIN_HIGH_HZ = 500f
        const val MAX_HZ = 20_000f
        const val MIN_RANGE_RATIO = 4f
        const val MIN_QUALITY = 1
        const val MAX_QUALITY = 4
    }
}
