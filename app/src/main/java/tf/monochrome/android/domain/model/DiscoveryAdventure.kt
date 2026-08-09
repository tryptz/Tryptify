package tf.monochrome.android.domain.model

import tf.monochrome.android.radio.RadioPlannerWeights
import kotlin.math.roundToInt

/**
 * How far from home the listener wants Discover to go, in 0..1.
 *
 * 0 is "more of what I already play" — new releases from artists in the
 * library, favourites, top artists. 1 is "show me things I don't know" —
 * neighbouring artists and whole genres the library barely touches. The
 * default sits just off centre, slightly toward the familiar, because a feed
 * that opens on strangers has nothing to anchor trust to.
 *
 * This is deliberately one number and not fourteen. The app already exposes
 * fourteen recommendation weights in Settings › Radio, and they are the right
 * controls for someone who wants them — but a discovery page needs a knob you
 * can move without a manual, and "familiar ↔ adventurous" is the axis people
 * actually think along. [toPlannerWeights] is the bridge: moving the knob also
 * moves the three planner weights that mean the same thing, so a station
 * started from Discover behaves like the feed it came from instead of
 * contradicting it.
 */
object DiscoveryAdventure {

    const val DEFAULT = 0.4f

    /** Total shelves the feed builds, at any setting. */
    const val SHELF_BUDGET = 8

    fun clamp(value: Float): Float = if (!value.isFinite()) DEFAULT else value.coerceIn(0f, 1f)

    /** Short label for the current position, for the control's own caption. */
    fun label(adventure: Float): String = when {
        adventure < 0.2f -> "Close to home"
        adventure < 0.45f -> "Mostly familiar"
        adventure < 0.7f -> "Balanced"
        adventure < 0.9f -> "Adventurous"
        else -> "Way out"
    }

    /**
     * How the shelf budget splits three ways at [adventure].
     *
     * Each band keeps at least one of every kind. A feed made entirely of
     * strangers is unusable, and a feed with no strangers in it isn't
     * discovery — so neither end is allowed to run the other to zero.
     */
    fun shelfMix(adventure: Float): ShelfMix {
        val a = clamp(adventure)
        // Explore grows from 1 to 4 shelves; genres from 1 to 3; the rest is
        // "new from artists you play", which shrinks as the other two grow.
        val explore = (1f + a * 3f).roundToInt().coerceIn(1, 4)
        val genre = (1f + a * 2f).roundToInt().coerceIn(1, 3)
        val familiar = (SHELF_BUDGET - explore - genre).coerceAtLeast(1)
        return ShelfMix(familiar = familiar, explore = explore, genre = genre)
    }

    /**
     * How far to walk the genre graph at [adventure].
     *
     * This is what finally gives the knob a metric instead of just a shelf
     * count. One hop is the genre and its immediate relatives — a fan of liquid
     * DnB gets atmospheric DnB. Three hops crosses into neighbouring scenes,
     * which is what "way out" should mean and what nothing in the app could
     * express before the graph existed.
     */
    fun maxHops(adventure: Float): Int = when {
        clamp(adventure) < 0.35f -> 1
        clamp(adventure) < 0.75f -> 2
        else -> 3
    }

    /**
     * How weak a graph edge may be and still count, at [adventure].
     *
     * Walking further is only half of it: the floor has to drop too, or extra
     * hops just find the same strong relatives by longer paths. Staying
     * strictly above zero keeps genuinely unrelated genres out at every
     * setting — adventurous is not the same as random.
     */
    fun neighbourFloor(adventure: Float): Float = 0.30f - clamp(adventure) * 0.18f

    /**
     * Fold [adventure] into the three planner weights that describe the same
     * axis, leaving the other eleven exactly as the user set them in
     * Settings › Radio.
     *
     * Ranges are chosen to stay inside the planner's own 0..3 clamp with room
     * to spare, so the extremes are still sane requests rather than saturated
     * ones.
     */
    fun toPlannerWeights(
        base: RadioPlannerWeights,
        adventure: Float,
    ): RadioPlannerWeights {
        val a = clamp(adventure)
        return base.copy(
            novelty = 0.60f + a * 1.20f,
            familiarity = 1.40f - a * 1.10f,
            discoveryDistance = 0.50f + a * 1.40f,
        ).clamped()
    }

    /** The shelf budget split, at one setting of the knob. */
    data class ShelfMix(
        /** "New from <artist you play>" shelves. */
        val familiar: Int,
        /** "Because you play <artist>" — neighbouring artists. */
        val explore: Int,
        /** Curated genre shelves. */
        val genre: Int,
    ) {
        val total: Int get() = familiar + explore + genre
    }
}
