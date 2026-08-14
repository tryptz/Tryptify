package tf.monochrome.android.radio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire encoder for every planner call.
 *
 * `encodeDefaults` so neutral weights are still sent explicitly — a weight the
 * user left at 1.0 still has to reach the server, because the server's own
 * default for that signal is not necessarily neutral. `ignoreUnknownKeys` so
 * server additions never break decoding, `explicitNulls` off so absent optional
 * context is omitted rather than sent as null.
 *
 * Shared (rather than private to [RadioPlannerClient]) so tests assert against
 * the encoder that actually produces request bodies instead of a lookalike that
 * could drift from it.
 */
internal val plannerJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Compact identity for a track, used for MetaBrainz matching on the planner
 * side. Only identifiers already on-device are sent — the app never downloads
 * MetaBrainz datasets itself.
 */
@Serializable
data class PlannerTrackIdentity(
    val title: String,
    val artist: String,
    val album: String? = null,
    val isrc: String? = null,
    @SerialName("musicbrainz_recording_id") val musicBrainzRecordingId: String? = null,
)

@Serializable
data class PlannerMetaBrainzContext(
    @SerialName("seed_identities") val seedIdentities: List<PlannerTrackIdentity> = emptyList(),
    @SerialName("history_identities") val historyIdentities: List<PlannerTrackIdentity> = emptyList(),
)

@Serializable
data class PlannerHistoryItem(
    val title: String,
    val artist: String,
)

/**
 * Request body for `POST /api/radio/plan`. `seed` is the free-text seed the
 * service was built around; the remaining fields are additive context the
 * server may use or ignore. [RadioPlannerClient] retries with a seed-only
 * body if a stricter server rejects the extended shape.
 */
@Serializable
data class RadioPlanRequest(
    val seed: String,
    val history: List<PlannerHistoryItem> = emptyList(),
    val weights: RadioPlannerWeights = RadioPlannerWeights(),
    val metabrainz: PlannerMetaBrainzContext? = null,
    // Target catalog the client resolves candidates against. Tryptify radio
    // is Qobuz-specific (trypt-hifi instance), so the planner can bias its
    // hints toward what that catalog actually carries.
    val catalog: String = "qobuz",
)

/** One track hint the planner suggests. Advisory — Android resolves playability. */
@Serializable
data class PlannerCandidateHint(
    val title: String? = null,
    val artist: String? = null,
    val isrc: String? = null,
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    val reason: String? = null,
)

/**
 * Planner response. Every field is optional/defaulted so old and new server
 * versions both decode; unknown keys are ignored by the client's Json config.
 */
@Serializable
data class RadioPlanResponse(
    val queries: List<String> = emptyList(),
    @SerialName("source_boosts") val sourceBoosts: Map<String, Float> = emptyMap(),
    @SerialName("candidate_hints") val candidateHints: List<PlannerCandidateHint> = emptyList(),
    @SerialName("fallback_reason") val fallbackReason: String? = null,
)

/** Subset of `GET /health` used by the settings connection test. */
@Serializable
data class PlannerHealth(
    val status: String? = null,
    val planner: String? = null,
    @SerialName("model_loaded") val modelLoaded: Boolean = false,
    @SerialName("metabrainz_enabled") val metabrainzEnabled: Boolean = false,
    @SerialName("metabrainz_index_exists") val metabrainzIndexExists: Boolean = false,
)
