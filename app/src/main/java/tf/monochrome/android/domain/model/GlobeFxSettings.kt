package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.ln

/**
 * How the world globe's coastlines and borders move to the music.
 *
 * Two waves run along every outline, and they are two different gestures rather
 * than one with a volume knob. [swell] is the long one — a slow undulation
 * carried by the bass, the coast breathing. [shimmer] is the short one, riding
 * the top octaves, and it reads as a vibration rather than a movement. Splitting
 * them is the whole design: a single wave driven by overall level just makes the
 * map wobble, and a wobbling map is a broken map. Two bands mean a bassline
 * moves continents and a hi-hat only makes their edges buzz.
 *
 * Persisted as one JSON blob, like the Player Visuals settings, so adding a
 * parameter later doesn't need a new DataStore key and old installs decode into
 * the current defaults.
 */
@Serializable
data class GlobeFxSettings(
    /**
     * Master amount. Zero is genuinely off — the analyzer stake is never taken
     * and the per-frame loop never starts, so the globe costs exactly what it
     * cost before the effect existed.
     */
    val amount: Float = 0.5f,
    /** Bass-driven long wave — the undulation. */
    val swell: Float = 1f,
    /** Treble-driven short wave — the vibration. */
    val shimmer: Float = 0.6f,
    /** How fast the waves travel along the outline. */
    val waveSpeed: Float = 1f,
    /** Spatial frequency — how many crests fit along a stretch of coast. */
    val waveDetail: Float = 1f,
    /**
     * The zoom at which the effect reaches full strength.
     *
     * Below it the amplitude ramps down to nothing. This is the readability
     * knob: at whole-Earth zoom the coastline's ~5,100 points are already
     * crushed into a few hundred pixels, every outline is near its own
     * neighbour, and displacing them by anything at all turns Europe into
     * static.
     *
     * At or below the globe's own minimum zoom the ramp is off entirely and the
     * effect runs everywhere — which looks alive and reads badly, and is the
     * user's call to make rather than ours. [MIN_RAMP] is that point, and it
     * tracks the screen's MIN_ZOOM: a ramp that finishes before the widest view
     * has no travel left to do anything with.
     */
    val fullEffectZoom: Float = 6f,
) {
    /** Off means off: no stake on the FFT tap, no frame loop, no extra draws. */
    val enabled: Boolean get() = amount > 0.01f && (swell > 0.001f || shimmer > 0.001f)

    fun clamped(): GlobeFxSettings = copy(
        amount = amount.coerceIn(0f, 1f),
        swell = swell.coerceIn(0f, 1.5f),
        shimmer = shimmer.coerceIn(0f, 1.5f),
        waveSpeed = waveSpeed.coerceIn(0.25f, 3f),
        waveDetail = waveDetail.coerceIn(0.3f, 3f),
        fullEffectZoom = fullEffectZoom.coerceIn(MIN_RAMP, 24f),
    )

    /**
     * How much of the effect survives at [zoom], in 0..1.
     *
     * Ramped on log zoom rather than linear because the zoom range itself is
     * logarithmic in feel — 0.85 to 48 — and a linear ramp spends nine tenths of
     * its travel in the close range where the answer is already 1. Smoothstepped
     * so the effect fades in rather than switching on at a threshold the user
     * would be able to feel as a step while pinching.
     */
    fun zoomGain(zoom: Float, minZoom: Float): Float {
        // A target at or below the widest view leaves the ramp no travel, so it
        // is off rather than infinitely steep. Checked before the division, not
        // after: the alternative is a zero span and a NaN gain multiplied into
        // every vertex on the globe.
        if (fullEffectZoom <= minZoom) return 1f
        val floor = ln(1f + minZoom)
        val span = ln(1f + fullEffectZoom) - floor
        if (span <= 1e-4f) return 1f
        val t = ((ln(1f + zoom) - floor) / span).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        /**
         * Below this the ramp is off. Mirrors MIN_ZOOM in the globe renderer —
         * the widest the Earth is ever drawn.
         */
        const val MIN_RAMP = 0.85f
    }
}
