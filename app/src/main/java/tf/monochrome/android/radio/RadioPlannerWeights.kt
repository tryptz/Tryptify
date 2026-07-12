package tf.monochrome.android.radio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Neutral weight — the planner treats the signal at its default strength. */
const val PLANNER_WEIGHT_NEUTRAL = 1.0f

/** Weights are clamped to this range on both the client and the server. */
const val PLANNER_WEIGHT_MIN = 0.0f
const val PLANNER_WEIGHT_MAX = 3.0f

/**
 * User-editable planner weights sent with every radio plan request.
 * `0.0` down-ranks a signal, `1.0` is neutral, values above `1.0` strengthen
 * it. Serial names are snake_case to match the planner service's model.
 */
@Serializable
data class RadioPlannerWeights(
    @SerialName("local_library") val localLibrary: Float = 1.20f,
    @SerialName("qobuz") val qobuz: Float = 1.00f,
    @SerialName("spotify_discovery") val spotifyDiscovery: Float = 1.00f,
    @SerialName("metabrainz_metadata") val metabrainzMetadata: Float = 1.00f,
    @SerialName("listenbrainz_graph") val listenbrainzGraph: Float = 0.90f,
    @SerialName("canonical_version_bias") val canonicalVersionBias: Float = 1.20f,
    @SerialName("novelty") val novelty: Float = 1.10f,
    @SerialName("familiarity") val familiarity: Float = 0.80f,
    @SerialName("artist_similarity") val artistSimilarity: Float = 1.00f,
    @SerialName("genre_tag_similarity") val genreTagSimilarity: Float = 1.00f,
    @SerialName("mood_continuity") val moodContinuity: Float = 0.85f,
    @SerialName("era_consistency") val eraConsistency: Float = 0.70f,
    @SerialName("avoid_recently_played") val avoidRecentlyPlayed: Float = 1.30f,
    @SerialName("discovery_distance") val discoveryDistance: Float = 1.00f,
) {
    /**
     * Returns a copy safe to persist and send: every weight coerced into
     * [PLANNER_WEIGHT_MIN]..[PLANNER_WEIGHT_MAX], non-finite values replaced
     * by that field's default so a corrupted setting can't wedge the planner.
     */
    fun clamped(): RadioPlannerWeights {
        val defaults = DEFAULT
        return RadioPlannerWeights(
            localLibrary = localLibrary.asPlannerWeight(defaults.localLibrary),
            qobuz = qobuz.asPlannerWeight(defaults.qobuz),
            spotifyDiscovery = spotifyDiscovery.asPlannerWeight(defaults.spotifyDiscovery),
            metabrainzMetadata = metabrainzMetadata.asPlannerWeight(defaults.metabrainzMetadata),
            listenbrainzGraph = listenbrainzGraph.asPlannerWeight(defaults.listenbrainzGraph),
            canonicalVersionBias = canonicalVersionBias.asPlannerWeight(defaults.canonicalVersionBias),
            novelty = novelty.asPlannerWeight(defaults.novelty),
            familiarity = familiarity.asPlannerWeight(defaults.familiarity),
            artistSimilarity = artistSimilarity.asPlannerWeight(defaults.artistSimilarity),
            genreTagSimilarity = genreTagSimilarity.asPlannerWeight(defaults.genreTagSimilarity),
            moodContinuity = moodContinuity.asPlannerWeight(defaults.moodContinuity),
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
