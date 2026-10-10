package tf.monochrome.android.radio

/** Weights are clamped to this range. */
const val PLANNER_WEIGHT_MIN = 0.0f
const val PLANNER_WEIGHT_MAX = 3.0f

/**
 * The weights radio ranks candidates with. `0.0` down-ranks a signal, `1.0` is
 * neutral, values above `1.0` strengthen it. Every one of these is scored
 * on-device by [LocalRadioPlanner].
 *
 * Nobody edits these directly any more: Settings › Radio shows [RadioStyle],
 * three dials and three switches, and writes them down as these weights. They
 * stay the stored and synced form, so nothing about persistence changed.
 *
 * Three more used to live here — MetaBrainz metadata, ListenBrainz graph and
 * mood continuity — describing datasets that are not on the device. They were
 * only ever serialised to the remote planner, so they went out with it rather
 * than stay as sliders that move nothing.
 */
data class RadioPlannerWeights(
    val localLibrary: Float = 1.20f,
    val qobuz: Float = 1.00f,
    val spotifyDiscovery: Float = 1.00f,
    val canonicalVersionBias: Float = 1.20f,
    val novelty: Float = 1.10f,
    val familiarity: Float = 0.80f,
    val artistSimilarity: Float = 1.00f,
    val genreTagSimilarity: Float = 1.00f,
    val eraConsistency: Float = 0.70f,
    val avoidRecentlyPlayed: Float = 1.30f,
    val discoveryDistance: Float = 1.00f,
) {
    /**
     * Returns a copy safe to persist: every weight coerced into
     * [PLANNER_WEIGHT_MIN]..[PLANNER_WEIGHT_MAX], non-finite values replaced
     * by that field's default so a corrupted setting can't wedge ranking.
     */
    fun clamped(): RadioPlannerWeights {
        val defaults = DEFAULT
        return RadioPlannerWeights(
            localLibrary = localLibrary.asPlannerWeight(defaults.localLibrary),
            qobuz = qobuz.asPlannerWeight(defaults.qobuz),
            spotifyDiscovery = spotifyDiscovery.asPlannerWeight(defaults.spotifyDiscovery),
            canonicalVersionBias = canonicalVersionBias.asPlannerWeight(defaults.canonicalVersionBias),
            novelty = novelty.asPlannerWeight(defaults.novelty),
            familiarity = familiarity.asPlannerWeight(defaults.familiarity),
            artistSimilarity = artistSimilarity.asPlannerWeight(defaults.artistSimilarity),
            genreTagSimilarity = genreTagSimilarity.asPlannerWeight(defaults.genreTagSimilarity),
            eraConsistency = eraConsistency.asPlannerWeight(defaults.eraConsistency),
            avoidRecentlyPlayed = avoidRecentlyPlayed.asPlannerWeight(defaults.avoidRecentlyPlayed),
            discoveryDistance = discoveryDistance.asPlannerWeight(defaults.discoveryDistance),
        )
    }

    companion object {
        val DEFAULT = RadioPlannerWeights()
    }
}

private fun Float.asPlannerWeight(default: Float): Float =
    if (!isFinite()) default else coerceIn(PLANNER_WEIGHT_MIN, PLANNER_WEIGHT_MAX)
