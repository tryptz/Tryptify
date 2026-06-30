package tf.monochrome.android.radio.planner

import kotlinx.serialization.Serializable

@Serializable
data class RadioPlannerWeights(
    val localLibrary: Float = 1.20f,
    val qobuz: Float = 1.00f,
    val spotifyDiscovery: Float = 1.00f,
    val metabrainzMetadata: Float = 1.00f,
    val listenbrainzGraph: Float = 0.90f,
    val canonicalVersionBias: Float = 1.20f,
    val novelty: Float = 1.10f,
    val familiarity: Float = 0.80f,
    val artistSimilarity: Float = 1.00f,
    val genreTagSimilarity: Float = 1.00f,
    val moodContinuity: Float = 0.85f,
    val eraConsistency: Float = 0.70f,
    val avoidRecentlyPlayed: Float = 1.30f,
    val discoveryDistance: Float = 1.00f,
) {
    fun clamped(): RadioPlannerWeights {
        val defaults = RadioPlannerWeights()
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

    fun toPlannerSliders(): Map<String, Float> {
        val weights = clamped()
        return mapOf(
            "localLibrary" to weights.localLibrary,
            "qobuz" to weights.qobuz,
            "spotifyDiscovery" to weights.spotifyDiscovery,
            "metabrainzMetadata" to weights.metabrainzMetadata,
            "listenbrainzGraph" to weights.listenbrainzGraph,
            "canonicalVersionBias" to weights.canonicalVersionBias,
            "novelty" to weights.novelty,
            "familiarity" to weights.familiarity,
            "artistSimilarity" to weights.artistSimilarity,
            "genreTagSimilarity" to weights.genreTagSimilarity,
            "moodContinuity" to weights.moodContinuity,
            "eraConsistency" to weights.eraConsistency,
            "avoidRecentlyPlayed" to weights.avoidRecentlyPlayed,
            "discoveryDistance" to weights.discoveryDistance,
        )
    }
}

private fun Float.asPlannerWeight(default: Float): Float =
    if (isNaN() || isInfinite()) default else coerceIn(0f, 3f)
