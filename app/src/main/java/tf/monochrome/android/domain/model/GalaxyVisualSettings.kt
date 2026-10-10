package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * How the genre galaxy looks, as the listener tunes it from the map.
 *
 * Every default is the galaxy as it shipped, so nothing changes for anyone who
 * never opens the sheet. These are wishes, not overrides: low-performance mode
 * still turns the heavy effects off whatever they say, because that switch is
 * the device asking for less work.
 *
 * Stored as JSON and read back through [clamped], so a value from an older or
 * hand-edited copy can never push the map outside what it can draw.
 */
@Serializable
data class GalaxyVisualSettings(
    // The sky.
    val deepSky: Boolean = true,
    /** Nebulae behind the families, as a share of their usual strength. */
    val nebulae: Float = 1f,
    val dust: GalaxyAmount = GalaxyAmount.NORMAL,
    // The core.
    val blackHole: Boolean = true,
    val godRays: Boolean = true,
    /** The rays' exposure, as a share of the usual. */
    val rayStrength: Float = 1f,
    // Motion.
    /** How fast the galaxy turns, as a share of the usual; 0 holds it still. */
    val spin: Float = 1f,
    val twinkle: Boolean = true,
    val travelBlur: Boolean = true,
    // Music.
    val smoke: Boolean = true,
    /** How thick and bright the gas is, as a share of the usual. */
    val smokeAmount: Float = 1f,
    val musicReactive: Boolean = true,
    /** How hard the gas and the black hole answer the music, as a share of the usual. */
    val reactivity: Float = 1f,
    // The stars.
    /** Star size, as a share of the usual. */
    val starSize: Float = 1f,
    val labels: GalaxyAmount = GalaxyAmount.NORMAL,
    val planets: Boolean = true,
) {
    fun clamped(): GalaxyVisualSettings = copy(
        nebulae = nebulae.finiteOr(1f).coerceIn(NEBULAE_RANGE),
        rayStrength = rayStrength.finiteOr(1f).coerceIn(RAY_RANGE),
        spin = spin.finiteOr(1f).coerceIn(SPIN_RANGE),
        smokeAmount = smokeAmount.finiteOr(1f).coerceIn(SMOKE_RANGE),
        reactivity = reactivity.finiteOr(1f).coerceIn(REACTIVITY_RANGE),
        starSize = starSize.finiteOr(1f).coerceIn(STAR_SIZE_RANGE),
    )

    companion object {
        val DEFAULT = GalaxyVisualSettings()

        val NEBULAE_RANGE = 0f..2f
        val RAY_RANGE = 0.3f..1.6f
        val SPIN_RANGE = 0f..3f
        val SMOKE_RANGE = 0.3f..2f
        val REACTIVITY_RANGE = 0.3f..2f
        val STAR_SIZE_RANGE = 0.6f..1.6f

        private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
    }
}

/** Less, the usual, or more: of dust grains, or of names on the map. */
@Serializable
enum class GalaxyAmount { LESS, NORMAL, MORE }
