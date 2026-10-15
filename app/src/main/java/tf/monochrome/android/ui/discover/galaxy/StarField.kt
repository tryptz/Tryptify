package tf.monochrome.android.ui.discover.galaxy

/**
 * What a galaxy is made of, before it is drawn: its stars, who they are, how
 * big, which family each belongs to, how they link up, and where they sit.
 *
 * The genre galaxy and the library galaxy are two of these
 * ([GenreStarField], [LibraryStarField]) drawn by one [GalaxyScene] and one
 * view: everything round the stars — the dust, the core, the halo, the sky,
 * the nebulae — is the scene's, and the same for both.
 *
 * Arrays are indexed by star, in [ids] order.
 */
class StarField(
    /** Each star's id, unique: a genre id, or `artist:` and the artist's normalised name. */
    val ids: List<String>,
    /** What a star is called on the map. */
    val names: List<String>,
    /** Family ids in a fixed order; [family] indexes into it. */
    val families: List<String>,
    val family: IntArray,
    /** How big each star is, 0..1: its radius is [GalaxyScene.starRadius] of this. */
    val prominence: FloatArray,
    /** The year each star began: where it sits along the timeline. */
    val startYear: IntArray,
    /** Links as (a, b) index pairs: a genre and its parent, or two artists who work together. */
    val links: IntArray,
    val layout: StarLayout,
    /**
     * Each star's twinkle phase, radians. Null draws them from the scene's
     * own seeded stream, which is how the genre galaxy has always had them;
     * a field that grows ([LibraryStarField]) gives its own, from each star's
     * key, so a new star leaves every other one as it was.
     */
    val phase: FloatArray? = null,
    /** How big a system round one of these stars can get: what the camera frames and the light reaches. */
    val systemLimits: SystemLimits = SystemLimits.CHART,
) {
    val size: Int get() = ids.size

    init {
        require(names.size == size && family.size == size && prominence.size == size && startYear.size == size) {
            "every per-star array has one entry per star"
        }
        require(links.size % 2 == 0) { "links are pairs" }
        require(phase == null || phase.size == size) { "one phase per star" }
    }
}

/**
 * Where a [StarField]'s stars go, in both of the galaxy's layouts: *sound*
 * (morph 0, the disc) and *time* (morph 1, the spiral).
 */
fun interface StarLayout {
    /**
     * Writes each star's position (x, y, z) in the sound layout to [sound] and
     * in the time layout to [time], and returns the angle the time track
     * leaves the core at. [gauss] is the scene's own seeded stream, for a
     * layout that has always drawn from it; a layout that must stay put as
     * stars are added seeds itself per star instead.
     */
    fun place(field: StarField, gauss: () -> Float, sound: FloatArray, time: FloatArray): Float
}
