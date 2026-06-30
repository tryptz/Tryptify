package tf.monochrome.android.radio.planner

import kotlinx.serialization.Serializable

@Serializable
data class RadioPlannerRequest(
    val seed: String? = null,
    val localMetadata: PlannerLocalMetadata = PlannerLocalMetadata(),
    val spotifyContext: PlannerSpotifyContext = PlannerSpotifyContext(),
    val qobuzContext: PlannerQobuzContext = PlannerQobuzContext(),
    val internetContext: Map<String, String> = emptyMap(),
    val settings: PlannerSettings = PlannerSettings(),
    val weights: RadioPlannerWeights = RadioPlannerWeights(),
    val sliders: Map<String, Float> = emptyMap(),
    val preset: Map<String, String> = emptyMap(),
    val sessionHistory: PlannerSessionHistory = PlannerSessionHistory(),
    val candidateSummary: PlannerCandidateSummary = PlannerCandidateSummary(),
    val metabrainz: PlannerMetaBrainzContext? = null,
)

@Serializable
data class PlannerLocalMetadata(
    val seedTracks: List<PlannerTrackMetadata> = emptyList(),
)

@Serializable
data class PlannerSpotifyContext(
    val seedSpotifyIds: List<String> = emptyList(),
    val recentSpotifyIds: List<String> = emptyList(),
    val topSpotifyIds: List<String> = emptyList(),
)

@Serializable
data class PlannerQobuzContext(
    val preferred: Boolean = true,
)

@Serializable
data class PlannerSettings(
    val targetTrackCount: Int = 12,
    val discoveryRatio: Float = 0.35f,
    val familiarityRatio: Float = 0.55f,
    val qobuzPreference: Float = 0.75f,
)

@Serializable
data class PlannerSessionHistory(
    val tracks: List<PlannerTrackMetadata> = emptyList(),
)

@Serializable
data class PlannerCandidateSummary(
    val localCandidateCount: Int = 0,
    val spotifyCandidateCount: Int = 0,
    val qobuzCandidateCount: Int = 0,
    val targetTrackCount: Int = 12,
)

@Serializable
data class PlannerTrackMetadata(
    val title: String = "",
    val artistName: String? = null,
    val albumTitle: String? = null,
    val isrc: String? = null,
    val source: String? = null,
)

@Serializable
data class PlannerTrackIdentity(
    val title: String,
    val artist: String,
    val album: String? = null,
    val isrc: String? = null,
    val musicBrainzRecordingId: String? = null,
    val musicBrainzReleaseId: String? = null,
    val musicBrainzArtistIds: List<String> = emptyList(),
)

@Serializable
data class PlannerMetaBrainzContext(
    val seedIdentities: List<PlannerTrackIdentity> = emptyList(),
    val localIdentities: List<PlannerTrackIdentity> = emptyList(),
    val historyIdentities: List<PlannerTrackIdentity> = emptyList(),
)

@Serializable
data class RadioPlan(
    val seedInterpretation: String = "",
    val queryMix: QueryMix = QueryMix(),
    val playlistShape: PlaylistShape = PlaylistShape(),
    val sourceWeights: SourceWeights = SourceWeights(),
    val scoringHints: ScoringHints = ScoringHints(),
    val refreshAdvice: RefreshAdvice = RefreshAdvice(),
    val safety: PlanSafety = PlanSafety(),
) {
    val isUseful: Boolean
        get() = queryMix.allQueries.isNotEmpty() || scoringHints.hasBoosts
}

@Serializable
data class QueryMix(
    val exactQueries: List<String> = emptyList(),
    val artistQueries: List<String> = emptyList(),
    val albumQueries: List<String> = emptyList(),
    val genreQueries: List<String> = emptyList(),
    val moodQueries: List<String> = emptyList(),
    val eraQueries: List<String> = emptyList(),
    val qobuzQueries: List<String> = emptyList(),
    val spotifyQueries: List<String> = emptyList(),
    val localLibraryQueries: List<String> = emptyList(),
) {
    val allQueries: List<String>
        get() = (exactQueries + artistQueries + albumQueries + genreQueries + moodQueries + eraQueries + qobuzQueries + spotifyQueries + localLibraryQueries)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
}

@Serializable
data class PlaylistShape(
    val energyCurve: String = "STEADY",
    val familiarityRatio: Float = 0.55f,
    val discoveryRatio: Float = 0.35f,
    val qobuzPreference: Float = 0.75f,
    val maxArtistRepeat: Int = 2,
    val targetTrackCount: Int = 12,
    val notes: String = "",
)

@Serializable
data class SourceWeights(
    val spotifyTaste: Float = 0.35f,
    val qobuzCatalog: Float = 0.40f,
    val localLibrary: Float = 0.20f,
    val internetContext: Float = 0.05f,
)

@Serializable
data class ScoringHints(
    val boostArtists: List<String> = emptyList(),
    val boostGenres: List<String> = emptyList(),
    val boostMoods: List<String> = emptyList(),
    val avoidArtists: List<String> = emptyList(),
    val avoidGenres: List<String> = emptyList(),
    val avoidKeywords: List<String> = emptyList(),
    val preferredEra: List<String> = emptyList(),
) {
    val hasBoosts: Boolean
        get() = boostArtists.isNotEmpty() || boostGenres.isNotEmpty() || boostMoods.isNotEmpty() || preferredEra.isNotEmpty()
}

@Serializable
data class RefreshAdvice(
    val refreshType: String = "SOFT",
    val reason: String = "",
    val shouldFetchNewInternetContext: Boolean = false,
    val shouldIncreaseDiscovery: Boolean = false,
    val shouldTightenMood: Boolean = false,
)

@Serializable
data class PlanSafety(
    val confidence: Float = 0.0f,
    val needsFallback: Boolean = false,
    val fallbackReason: String? = null,
)
