package tf.monochrome.android.ui.discover.galaxy

/**
 * How a planet's sound is drawn: the numbers the view turns [PlanetSystem.Planet.tone],
 * [haze][PlanetSystem.Planet.haze], [spinPeriod][PlanetSystem.Planet.spinPeriod]
 * and [shade][PlanetSystem.Planet.shade] into. Pure, so they are tested, and
 * each is exactly the old look at the old defaults, which is what every chart
 * planet has.
 */
object PlanetLook {

    /** The atmosphere's saturation every planet had, and still has with no key. */
    const val ATMOSPHERE_SATURATION = 0.45f

    /**
     * The atmosphere's saturation for [tone]: a minor key deeper (0.6), a
     * major one paler (0.3), [ATMOSPHERE_SATURATION] with none.
     */
    fun atmosphereSaturation(tone: Float): Float =
        (ATMOSPHERE_SATURATION - TONE_SATURATION * tone.coerceIn(-1f, 1f)).coerceIn(0f, 1f)

    /** The atmosphere's brightness for [tone]: full, as it was, and a little lower for a minor key. */
    fun atmosphereValue(tone: Float): Float = 1f - TONE_DARKEN * (-tone).coerceIn(0f, 1f)

    /**
     * How far past the planet a [haze] of 1 (the loudest master) widens its
     * atmosphere, in planet radii: added to the glow's spread, as heat is.
     */
    const val HAZE_SPREAD = 0.35f

    /**
     * The cover's turn at [time] s, degrees, for a planet turning once every
     * [period] s from its own [phase] (radians), so two albums at one tempo
     * do not turn in step. 0 for a planet that does not turn.
     */
    fun spinDegrees(time: Float, period: Float, phase: Float): Float {
        if (!(period > 0f) || !period.isFinite() || !time.isFinite()) return 0f
        val turns = (time / period) % 1f
        return (turns * 360f + Math.toDegrees(phase.toDouble()).toFloat()) % 360f
    }

    /** The shadow mask's alpha, from the body's [alpha] and its planet's [shade]. */
    fun shadeAlpha(alpha: Int, shade: Float): Int = (alpha * shade.coerceIn(0f, 1f)).toInt()

    private const val TONE_SATURATION = 0.15f
    private const val TONE_DARKEN = 0.15f
}
