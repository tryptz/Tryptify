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

    fun seeds(): List<RecommendationSeed> = runCatching {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        json.decodeFromString(ListSerializer(RecommendationSeed.serializer()), text)
            .filter { it.label.isNotBlank() && it.query.isNotBlank() }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULTS

    private companion object {
        const val ASSET_NAME = "qobuz_recommendations.json"
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
