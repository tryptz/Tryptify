package tf.monochrome.android.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A curated recommendation row: a display [label] and the Qobuz search [query] that fills it. */
@Serializable
data class RecommendationSeed(
    val label: String,
    val query: String,
)

/**
 * Loads the curated recommendation seeds shipped with the app
 * (assets/qobuz_recommendations.json). Editing that file changes the
 * "Recommended" rows shown in the search empty state. Falls back to a built-in
 * list if the asset is missing or unparseable.
 */
@Singleton
class RecommendationSeedsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun seeds(): List<RecommendationSeed> = load(ASSET_NAME) ?: DEFAULTS

    /**
     * Mood and activity entry points for the Discover chip rail
     * (assets/discovery_moods.json).
     *
     * Separate from [seeds] on purpose: a genre answers "what is this music",
     * a mood answers "what am I doing" — and the second is the question people
     * usually arrive with. Same shape, so the same shelf builder fills both.
     */
    fun moods(): List<RecommendationSeed> = load(MOODS_ASSET_NAME) ?: MOOD_DEFAULTS

    private fun load(assetName: String): List<RecommendationSeed>? = runCatching {
        val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
        json.decodeFromString(ListSerializer(RecommendationSeed.serializer()), text)
            .filter { it.label.isNotBlank() && it.query.isNotBlank() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val ASSET_NAME = "qobuz_recommendations.json"
        const val MOODS_ASSET_NAME = "discovery_moods.json"
        val MOOD_DEFAULTS = listOf(
            RecommendationSeed("Focus", "deep focus instrumental"),
            RecommendationSeed("Late night", "late night ambient downtempo"),
            RecommendationSeed("Workout", "high energy workout"),
            RecommendationSeed("Chill", "chill relaxing"),
            RecommendationSeed("Party", "party dance hits"),
            RecommendationSeed("Commute", "upbeat indie pop"),
        )
        val DEFAULTS = listOf(
            RecommendationSeed("Hardstyle", "hardstyle"),
            RecommendationSeed("Electronic", "electronic"),
            RecommendationSeed("Lo-fi beats", "lofi"),
            RecommendationSeed("Pop hits", "pop hits"),
            RecommendationSeed("Hip-hop", "hip hop"),
            RecommendationSeed("Jazz", "jazz"),
            RecommendationSeed("Classical", "classical"),
        )
    }
}
