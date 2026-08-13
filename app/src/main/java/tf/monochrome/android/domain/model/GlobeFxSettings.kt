package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * How the world globe's coastlines and borders are lit.
 *
 * The outlines are drawn in the map's neutral ink by default, which is honest
 * but inert — the dots carry the app's accent colour and the land they sit on
 * carries none of it. [illumination] moves the outlines toward that same accent
 * so the globe reads as one object in the app's palette rather than a grey map
 * with coloured pins on it, and [glowBrightness] lays a soft halo under the
 * stroke so the lit coast looks lit rather than merely recoloured.
 *
 * Everything here is static per camera. Nothing in this file reacts to audio,
 * and nothing in it should: the outlines are re-projected from ~5,100 points on
 * every draw, so anything that changes them per frame turns the globe into a
 * full redraw at 60 Hz, which is what it costs to move a coastline.
 *
 * Persisted as one JSON blob. Decoding ignores unknown keys, so a settings row
 * written by an older build simply falls back to these defaults.
 */
@Serializable
data class GlobeFxSettings(
    /**
     * How far the outlines move from the map's neutral ink toward the UI accent.
     * Zero leaves the globe exactly as it was drawn before any of this existed.
     */
    val illumination: Float = 0.55f,
    /** Strength of the halo laid under each outline. Zero draws no halo pass. */
    val glowBrightness: Float = 0.3f,
    /** Halo width, as a multiple of the outline's own stroke. */
    val glowWidth: Float = 3.5f,
    /**
     * How solidly the continents are filled over the ocean disc.
     *
     * Zero is the globe as it was before the land layer existed: an ocean with
     * outlines drawn on it and no way to tell, at a glance, which side of a
     * coast is which.
     */
    val landFill: Float = 0.3f,
) {
    /** Whether the halo pass is worth running at all. */
    val glowing: Boolean get() = glowBrightness > 0.01f && glowWidth > 1.01f

    fun clamped(): GlobeFxSettings = copy(
        illumination = illumination.coerceIn(0f, 1f),
        glowBrightness = glowBrightness.coerceIn(0f, 1f),
        glowWidth = glowWidth.coerceIn(1f, 8f),
        landFill = landFill.coerceIn(0f, 0.8f),
    )
}
