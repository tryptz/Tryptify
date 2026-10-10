package tf.monochrome.android.radio

import kotlin.math.abs

/**
 * Settings › Radio, as the listener sees it: three dials and three switches.
 *
 * It replaced eleven 0–3 weight sliders, and the eleven were worse than their
 * number. One ("Qobuz") moved nothing, because every candidate on a Qobuz
 * station is a Qobuz track. Two pairs were one setting split in half —
 * novelty and familiarity only ever mattered by their difference, and artist
 * similarity and discovery distance were exact mirrors that cancelled out at
 * their defaults. "Discovery expansion" boosted the seed artist's own search
 * results, so turning it up gave more of the same artist. Here each control is
 * one thing, named for what it does, with words at both ends instead of a
 * number in the middle.
 *
 * The planner still scores with [RadioPlannerWeights], and those are still what
 * is stored and synced: this is a view of them. [toWeights] writes the dials
 * down as weights and [fromWeights] reads stored weights back as dials, so
 * settings made with the old sliders carry over. [BALANCED] is exactly
 * [RadioPlannerWeights.DEFAULT], so a station nobody tuned ranks as it did.
 */
data class RadioStyle(
    /** [-NEWNESS_RANGE] songs you know … [NEWNESS_RANGE] songs you have not heard. */
    val newness: Float = BALANCED_NEWNESS,
    /** 0 the seed's artist and its closest peers … 1 further out. */
    val reach: Float = 0.5f,
    /** 0 loose … 1 the seed's genre and era. */
    val sound: Float = 0.5f,
    val preferLibrary: Boolean = true,
    val avoidRecent: Boolean = true,
    val preferOriginals: Boolean = true,
) {
    /** A copy with every dial in range; a non-finite one takes the balanced value. */
    fun clamped(): RadioStyle = copy(
        newness = newness.orElse(BALANCED_NEWNESS).coerceIn(-NEWNESS_RANGE, NEWNESS_RANGE),
        reach = reach.orElse(0.5f).coerceIn(0f, 1f),
        sound = sound.orElse(0.5f).coerceIn(0f, 1f),
    )

    /** The weights the planner scores with. */
    fun toWeights(): RadioPlannerWeights {
        val s = clamped()
        val d = RadioPlannerWeights.DEFAULT
        return RadioPlannerWeights(
            localLibrary = if (s.preferLibrary) d.localLibrary else 0f,
            // Moves nothing — see the class comment — so it is held neutral.
            qobuz = d.qobuz,
            // The seed artist's own search results: "more of this artist", so
            // it rises toward the close end of reach and is gone at the far end.
            spotifyDiscovery = 2f * (1f - s.reach) * d.spotifyDiscovery,
            canonicalVersionBias = if (s.preferOriginals) d.canonicalVersionBias else 0f,
            // Only the difference between these two changes a ranking: one is
            // added to songs not yet heard, the other to songs that have been.
            novelty = (NEWNESS_CENTRE + s.newness).coerceAtLeast(0f),
            familiarity = (NEWNESS_CENTRE - s.newness).coerceAtLeast(0f),
            // Mirrors: closeness + drift = 1 for a known artist, so their
            // weights split one budget between near and far.
            artistSimilarity = 2f * (1f - s.reach) * d.artistSimilarity,
            discoveryDistance = 2f * s.reach * d.discoveryDistance,
            genreTagSimilarity = 2f * s.sound * d.genreTagSimilarity,
            eraConsistency = 2f * s.sound * d.eraConsistency,
            avoidRecentlyPlayed = if (s.avoidRecent) d.avoidRecentlyPlayed else 0f,
        ).clamped()
    }

    /** Which preset this is, or null for a custom mix. */
    fun preset(): RadioPreset? = RadioPreset.entries.firstOrNull { it.style.sameDials(this) }

    /** This style with [preset]'s dials, keeping its switches — those are personal. */
    fun withPreset(preset: RadioPreset): RadioStyle = preset.style.copy(
        preferLibrary = preferLibrary,
        avoidRecent = avoidRecent,
        preferOriginals = preferOriginals,
    )

    private fun sameDials(other: RadioStyle): Boolean =
        abs(newness - other.newness) < DIAL_EPSILON &&
            abs(reach - other.reach) < DIAL_EPSILON &&
            abs(sound - other.sound) < DIAL_EPSILON

    companion object {
        /** Where novelty and familiarity sit when newness is 0. */
        private const val NEWNESS_CENTRE = 0.95f

        /**
         * How far newness goes either way: to where one of the pair reaches
         * zero, so each end of the dial is exactly one signal with the other
         * silent, and a stored end reads back as that end.
         */
        const val NEWNESS_RANGE = NEWNESS_CENTRE

        /** The default lean toward new songs: DEFAULT's 1.10 novelty over 0.80 familiarity. */
        const val BALANCED_NEWNESS = 0.15f

        /** Dials this close count as equal, so a stored preset reads back as itself. */
        private const val DIAL_EPSILON = 0.02f

        val BALANCED = RadioStyle()

        /**
         * Stored weights, read as dials.
         *
         * Exact for anything [toWeights] wrote. Weights set with the old
         * sliders are read by what they did: newness from the gap between
         * novelty and familiarity, reach from the split between similarity and
         * distance, sound from genre and era together, and each switch on when
         * its weight was meaningfully above zero.
         */
        fun fromWeights(weights: RadioPlannerWeights): RadioStyle {
            val w = weights.clamped()
            val d = RadioPlannerWeights.DEFAULT
            val near = w.artistSimilarity / d.artistSimilarity
            val far = w.discoveryDistance / d.discoveryDistance
            val genre = w.genreTagSimilarity / d.genreTagSimilarity
            val era = w.eraConsistency / d.eraConsistency
            return RadioStyle(
                newness = (w.novelty - w.familiarity) / 2f,
                reach = if (near + far > 0f) far / (near + far) else 0.5f,
                sound = (genre + era) / 4f,
                preferLibrary = w.localLibrary >= SWITCH_ON,
                avoidRecent = w.avoidRecentlyPlayed >= SWITCH_ON,
                preferOriginals = w.canonicalVersionBias >= SWITCH_ON,
            ).clamped()
        }

        /** A switch reads as on from this weight up: half the shipped strength or so. */
        private const val SWITCH_ON = 0.5f
    }
}

/** One-tap starting points for the three dials. */
enum class RadioPreset(val style: RadioStyle) {
    /** Songs you know, from the seed's artist and its nearest peers, in its sound. */
    FAMILIAR(RadioStyle(newness = -0.6f, reach = 0.2f, sound = 0.8f)),
    BALANCED(RadioStyle.BALANCED),
    /** Songs you have not heard, from further out, across genres and eras. */
    ADVENTUROUS(RadioStyle(newness = 0.7f, reach = 0.85f, sound = 0.25f)),
}

private fun Float.orElse(fallback: Float): Float = if (isFinite()) this else fallback
